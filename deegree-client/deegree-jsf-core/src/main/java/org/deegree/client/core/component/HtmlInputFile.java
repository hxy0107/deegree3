//$HeadURL: svn+ssh://lbuesching@svn.wald.intevation.de/deegree/base/trunk/resources/eclipse/files_template.xml $
/*----------------------------------------------------------------------------
 This file is part of deegree, http://deegree.org/
 Copyright (C) 2001-2010 by:
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
package org.deegree.client.core.component;

import javax.faces.application.FacesMessage;

import javax.faces.component.FacesComponent;
import javax.faces.component.UIInput;
import javax.faces.component.behavior.ClientBehaviorHolder;
import javax.faces.context.FacesContext;

import org.deegree.client.core.model.UploadedFile;
import org.deegree.client.core.utils.MessageUtils;

/**
 * 
 * UIInput component to enable a file upload in a form. The form must have multipart content
 * (enctype="multipart/form-data")
 * 
 * @author <a href="mailto:buesching@lat-lon.de">Lyn Buesching</a>
 * @author last edited by: $Author: lyn $
 * 
 * @version $Revision: $, $Date: $
 */
@FacesComponent(value = "HtmlInputFile")
public class HtmlInputFile extends UIInput implements ClientBehaviorHolder {

    private String styleClass;

    private String target;

    public HtmlInputFile() {
        setRendererType( "org.deegree.InputFile" );
    }

    public void setStyleClass( String styleClass ) {
        this.styleClass = styleClass;
    }

    public String getStyleClass() {
        return styleClass;
    }

    public void setTarget( String target ) {
        this.target = target;
    }

    public String getTarget() {
        return target;
    }

    @Override
    protected void validateValue( FacesContext context, Object value ) {
        if ( !isValid() ) {
            return;
        }
        if ( isRequired() && isUploadedFileEmpty( value ) ) {
            FacesMessage message = MessageUtils.getFacesMessage(
                                                                 null,
                                                                 FacesMessage.SEVERITY_ERROR,
                                                                 "org.deegree.client.core.component.HtmlInputFile.REQUIRED",
                                                                 getClientId() );
            context.addMessage( getClientId( context ), message );

            setValid( false );
            return;
        }

    }

    private boolean isUploadedFileEmpty( Object value ) {
        if ( value == null ) {
            return true;
        }
        if ( !( value instanceof UploadedFile ) ) {
            return true;
        }
        UploadedFile uploadedFile = (UploadedFile) value;
        if ( uploadedFile.getFileItem() == null ) {
            return true;
        }
        return false;
    }
}
