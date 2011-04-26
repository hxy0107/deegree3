//$HeadURL$
/*----------------------------------------------------------------------------
 This file is part of deegree, http://deegree.org/
 Copyright (C) 2001-2009 by:
 - Department of Geography, University of Bonn -
 and
 - lat/lon GmbH -

 This library is free software; you can redistribute it and/or modify it under
 the terms of the GNU Lesser General Public License as published by the Free
 Software Foundation; either version 2.1 of the License, or (at your option)
 any later version.
 This library is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 details.
 You should have received a copy of the GNU Lesser General Public License
 along with this library; if not, write to the Free Software Foundation, Inc.,
 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

 Contact information:

 lat/lon GmbH
 Aennchenstr. 19, 53177 Bonn
 Germany
 http://lat-lon.de/

 Department of Geography, University of Bonn
 Prof. Dr. Klaus Greve
 Postfach 1147, 53001 Bonn
 Germany
 http://www.geographie.uni-bonn.de/deegree/

 e-mail: info@deegree.org
 ----------------------------------------------------------------------------*/
package org.deegree.metadata.persistence.iso;

import static org.deegree.commons.jdbc.ConnectionManager.Type.PostgreSQL;
import static org.deegree.commons.utils.JDBCUtils.close;
import static org.slf4j.LoggerFactory.getLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.deegree.commons.config.DeegreeWorkspace;
import org.deegree.commons.config.ResourceInitException;
import org.deegree.commons.jdbc.ConnectionManager;
import org.deegree.commons.jdbc.ConnectionManager.Type;
import org.deegree.commons.utils.JDBCUtils;
import org.deegree.filter.FilterEvaluationException;
import org.deegree.filter.OperatorFilter;
import org.deegree.filter.sql.AbstractWhereBuilder;
import org.deegree.filter.sql.mssql.MSSQLWhereBuilder;
import org.deegree.filter.sql.postgis.PostGISWhereBuilder;
import org.deegree.metadata.ISORecord;
import org.deegree.metadata.i18n.Messages;
import org.deegree.metadata.persistence.MetadataQuery;
import org.deegree.metadata.persistence.MetadataResultSet;
import org.deegree.metadata.persistence.MetadataStore;
import org.deegree.metadata.persistence.MetadataStoreTransaction;
import org.deegree.metadata.persistence.inspectors.MetadataSchemaValidationInspector;
import org.deegree.metadata.persistence.inspectors.RecordInspector;
import org.deegree.metadata.persistence.iso.inspectors.CoupledDataInspector;
import org.deegree.metadata.persistence.iso.inspectors.FIInspector;
import org.deegree.metadata.persistence.iso.inspectors.HierarchyLevelInspector;
import org.deegree.metadata.persistence.iso.inspectors.InspireComplianceInspector;
import org.deegree.metadata.persistence.iso19115.jaxb.CoupledResourceInspector;
import org.deegree.metadata.persistence.iso19115.jaxb.FileIdentifierInspector;
import org.deegree.metadata.persistence.iso19115.jaxb.ISOMetadataStoreConfig;
import org.deegree.metadata.persistence.iso19115.jaxb.ISOMetadataStoreConfig.Inspectors;
import org.deegree.metadata.persistence.iso19115.jaxb.InspireInspector;
import org.deegree.metadata.persistence.iso19115.jaxb.SchemaValidator;
import org.deegree.protocol.csw.CSWConstants.ResultType;
import org.deegree.protocol.csw.MetadataStoreException;
import org.slf4j.Logger;

/**
 * {@link MetadataStore} implementation for accessing ISO 19115 records stored in spatial SQL databases (currently only
 * supports PostgreSQL / PostGIS).
 * 
 * @author <a href="mailto:thomas@lat-lon.de">Steffen Thomas</a>
 * @author last edited by: $Author$
 * 
 * @version $Revision$, $Date$
 */
public class ISOMetadataStore implements MetadataStore<ISORecord> {

    private static final Logger LOG = getLogger( ISOMetadataStore.class );

    private final String connectionId;

    // if true, use old-style for spatial predicates (intersects instead of ST_Intersecs)
    private boolean useLegacyPredicates;

    private ISOMetadataStoreConfig config;

    private Type connectionType;

    private final List<RecordInspector<ISORecord>> inspectorChain = new ArrayList<RecordInspector<ISORecord>>();

    /** Used to limit the fetch size for SELECT statements that potentially return a lot of rows. */
    public static final int DEFAULT_FETCH_SIZE = 100;

    /**
     * Creates a new {@link ISOMetadataStore} instance from the given JAXB configuration object.
     * 
     * @param config
     * @throws ResourceInitException
     */
    public ISOMetadataStore( ISOMetadataStoreConfig config ) throws ResourceInitException {
        this.connectionId = config.getJDBCConnId();
        this.config = config;

        // this.varToValue = new HashMap<String, String>();
        // String systemStartDate = "2010-11-16";
        // varToValue.put( "${SYSTEM_START_DATE}", systemStartDate );
        // build inspector chain
        Inspectors inspectors = config.getInspectors();
        if ( inspectors != null ) {
            FileIdentifierInspector fi = inspectors.getFileIdentifierInspector();
            InspireInspector ii = inspectors.getInspireInspector();
            CoupledResourceInspector cri = inspectors.getCoupledResourceInspector();
            SchemaValidator sv = inspectors.getSchemaValidator();
            if ( fi != null ) {
                inspectorChain.add( new FIInspector( fi ) );
            }
            if ( ii != null ) {
                inspectorChain.add( new InspireComplianceInspector( ii ) );
            }
            if ( cri != null ) {
                inspectorChain.add( new CoupledDataInspector( cri ) );
            }
            if ( sv != null ) {
                inspectorChain.add( new MetadataSchemaValidationInspector<ISORecord>() );
            }
        }
        // hard coded because there is no configuration planned
        inspectorChain.add( new HierarchyLevelInspector() );
    }

    /**
     * @return the db type, null, if unknown
     */
    public Type getDBType() {
        if ( connectionType == null ) {
            DeegreeWorkspace dw = DeegreeWorkspace.getInstance();
            ConnectionManager mgr = dw.getSubsystemManager( ConnectionManager.class );
            this.connectionType = mgr.getType( connectionId );
        }
        return connectionType;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.deegree.record.persistence.RecordStore#destroy()
     */
    @Override
    public void destroy() {
        LOG.debug( "destroy" );

    }

    /**
     * Returns the JDBC connection id.
     * 
     * @return the JDBC connection id, never <code>null</code>
     */
    public String getConnId() {
        return connectionId;
    }

    @Override
    public void init( DeegreeWorkspace workspace )
                            throws ResourceInitException {
        LOG.debug( "init" );
        ConnectionManager mgr = workspace.getSubsystemManager( ConnectionManager.class );
        connectionType = mgr.getType( connectionId );
        if ( connectionType == PostgreSQL ) {
            Connection conn = null;
            try {
                conn = getConnection();
                useLegacyPredicates = JDBCUtils.useLegayPostGISPredicates( conn, LOG );
            } catch ( Throwable e ) {
                LOG.debug( e.getMessage(), e );
                throw new ResourceInitException( e.getMessage(), e );
            } finally {
                close( conn );
            }
        }
    }

    private AbstractWhereBuilder getWhereBuilder( MetadataQuery query )
                            throws FilterEvaluationException {
        if ( getDBType() == PostgreSQL ) {
            PostGISMappingsISODC mapping = new PostGISMappingsISODC();
            return new PostGISWhereBuilder( mapping, (OperatorFilter) query.getFilter(), query.getSorting(),
                                            useLegacyPredicates );
        }
        if ( getDBType() == Type.MSSQL ) {
            MSSQLMappingsISODC mapping = new MSSQLMappingsISODC();
            return new MSSQLWhereBuilder( mapping, (OperatorFilter) query.getFilter(), query.getSorting() );
        }
        return null;
    }

    @Override
    public MetadataResultSet<ISORecord> getRecords( MetadataQuery query )
                            throws MetadataStoreException {
        String operationName = "getRecords";
        LOG.debug( Messages.getMessage( "INFO_EXEC", operationName ) );
        AbstractWhereBuilder builder = null;
        try {
            builder = getWhereBuilder( query );
        } catch ( FilterEvaluationException e ) {
            String msg = Messages.getMessage( "ERROR_OPERATION", operationName, e.getLocalizedMessage() );
            LOG.debug( msg );
            throw new MetadataStoreException( msg );
        }

        ResultSet rs = null;
        PreparedStatement preparedStatement = null;
        ExecuteStatements exe = new ExecuteStatements( getDBType() );
        Connection conn = getConnection();
        try {
            preparedStatement = exe.executeGetRecords( query, builder, conn );
            preparedStatement.setFetchSize( DEFAULT_FETCH_SIZE );
            rs = preparedStatement.executeQuery();
        } catch ( Throwable t ) {
            JDBCUtils.close( rs, preparedStatement, conn, LOG );
            String msg = Messages.getMessage( "ERROR_REQUEST_TYPE", ResultType.results.name(), t.getMessage() );
            LOG.debug( msg );
            throw new MetadataStoreException( msg );
        }
        return new ISOMetadataResultSet( rs, conn, preparedStatement );
    }

    /**
     * The mandatory "resultType" attribute in the GetRecords operation is set to "hits".
     * 
     * @throws MetadataStoreException
     */
    public int getRecordCount( MetadataQuery query )
                            throws MetadataStoreException {
        String resultTypeName = "hits";
        LOG.debug( Messages.getMessage( "INFO_EXEC", "do " + resultTypeName + " on getRecords" ) );
        ResultSet rs = null;
        PreparedStatement ps = null;
        int countRows = 0;
        Connection conn = null;
        try {
            AbstractWhereBuilder builder = getWhereBuilder( query );
            conn = getConnection();
            ps = new ExecuteStatements( getDBType() ).executeCounting( builder, conn );
            rs = ps.executeQuery();
            rs.next();
            countRows = rs.getInt( 1 );
            LOG.debug( "rs for rowCount: " + rs.getInt( 1 ) );
        } catch ( Throwable t ) {
            JDBCUtils.close( rs, ps, conn, LOG );
            String msg = Messages.getMessage( "ERROR_REQUEST_TYPE", ResultType.results.name(), t.getMessage() );
            LOG.debug( msg );
            throw new MetadataStoreException( msg );
        } finally {
            JDBCUtils.close( rs, ps, conn, LOG );
        }
        return countRows;
    }

    @Override
    public MetadataResultSet<ISORecord> getRecordById( List<String> idList )
                            throws MetadataStoreException {
        LOG.debug( Messages.getMessage( "INFO_EXEC", "getRecordsById" ) );
        String mainTable;
        String fileidentifier;
        String recordfull;
        if ( getDBType() == Type.MSSQL ) {
            mainTable = MSSQLMappingsISODC.DatabaseTables.idxtb_main.name();
            fileidentifier = MSSQLMappingsISODC.CommonColumnNames.fileidentifier.name();
            recordfull = MSSQLMappingsISODC.CommonColumnNames.recordfull.name();
        } else {
            mainTable = PostGISMappingsISODC.DatabaseTables.idxtb_main.name();
            fileidentifier = PostGISMappingsISODC.CommonColumnNames.fileidentifier.name();
            recordfull = PostGISMappingsISODC.CommonColumnNames.recordfull.name();
        }
        ResultSet rs = null;
        Connection conn = null;
        PreparedStatement stmt = null;
        try {
            int size = idList.size();
            conn = getConnection();

            StringBuilder select = new StringBuilder();
            select.append( "SELECT " ).append( recordfull );
            select.append( " FROM " ).append( mainTable );
            select.append( " WHERE " );
            for ( int iter = 0; iter < size; iter++ ) {
                select.append( fileidentifier ).append( " = ? " );
                if ( iter < size - 1 ) {
                    select.append( " OR " );
                }
            }

            stmt = conn.prepareStatement( select.toString() );
            stmt.setFetchSize( DEFAULT_FETCH_SIZE );
            LOG.debug( "select RecordById statement: " + stmt );

            int i = 1;
            for ( String identifier : idList ) {
                stmt.setString( i, identifier );
                LOG.debug( "identifier: " + identifier );
                LOG.debug( "" + stmt );
                i++;
            }
            rs = stmt.executeQuery();

        } catch ( Throwable t ) {
            JDBCUtils.close( rs, stmt, conn, LOG );
            String msg = Messages.getMessage( "ERROR_REQUEST_TYPE", ResultType.results.name(), t.getMessage() );
            LOG.debug( msg );
            throw new MetadataStoreException( msg );
        }
        return new ISOMetadataResultSet( rs, conn, stmt );
    }

    @Override
    public MetadataStoreTransaction acquireTransaction()
                            throws MetadataStoreException {
        ISOMetadataStoreTransaction ta = null;
        Connection conn = getConnection();
        try {
            ta = new ISOMetadataStoreTransaction( conn, inspectorChain, config.getAnyText(), useLegacyPredicates,
                                                  getDBType() );
        } catch ( Throwable e ) {
            throw new MetadataStoreException( e.getMessage() );
        }
        return ta;
    }

    /**
     * Acquires a new JDBC connection from the configured connection pool.
     * <p>
     * The returned connection has auto commit off to make it suitable for dealing with very large result sets
     * (cursor-based results). If auto commit is not disabled, some JDBC drivers (e.g. PostGIS) will always fetch all
     * rows at once (which will lead to out of memory errors). See <a
     * href="http://jdbc.postgresql.org/documentation/81/query.html"/> for more information. Note that it may still be
     * necessary to set the fetch size and to close the connection to return it to the pool.
     * </p>
     * 
     * @return connection with auto commit set to off, never <code>null</code>
     * @throws MetadataStoreException
     */
    private Connection getConnection()
                            throws MetadataStoreException {
        Connection conn = null;
        try {
            conn = ConnectionManager.getConnection( connectionId );
            conn.setAutoCommit( false );
        } catch ( Throwable e ) {
            throw new MetadataStoreException( e.getMessage() );
        }
        return conn;
    }
}