/**
 * Copyright 2005-2010 Noelios Technologies.
 * 
 * The contents of this file are subject to the terms of one of the following
 * open source licenses: LGPL 3.0 or LGPL 2.1 or CDDL 1.0 or EPL 1.0 (the
 * "Licenses"). You can select the license that you prefer but you may not use
 * this file except in compliance with one of these Licenses.
 * 
 * You can obtain a copy of the LGPL 3.0 license at
 * http://www.opensource.org/licenses/lgpl-3.0.html
 * 
 * You can obtain a copy of the LGPL 2.1 license at
 * http://www.opensource.org/licenses/lgpl-2.1.php
 * 
 * You can obtain a copy of the CDDL 1.0 license at
 * http://www.opensource.org/licenses/cddl1.php
 * 
 * You can obtain a copy of the EPL 1.0 license at
 * http://www.opensource.org/licenses/eclipse-1.0.php
 * 
 * See the Licenses for the specific language governing permissions and
 * limitations under the Licenses.
 * 
 * Alternatively, you can obtain a royalty free commercial license with less
 * limitations, transferable or non-transferable, directly at
 * http://www.noelios.com/products/restlet-engine
 * 
 * Restlet is a registered trademark of Noelios Technologies.
 */

package org.restlet.ext.odata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.restlet.Context;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.data.ChallengeResponse;
import org.restlet.data.CharacterSet;
import org.restlet.data.Form;
import org.restlet.data.MediaType;
import org.restlet.data.Preference;
import org.restlet.data.Reference;
import org.restlet.data.Tag;
import org.restlet.engine.http.header.HeaderConstants;
import org.restlet.ext.atom.Content;
import org.restlet.ext.atom.Entry;
import org.restlet.ext.atom.Feed;
import org.restlet.ext.odata.internal.EntryContentHandler;
import org.restlet.ext.odata.internal.edm.AssociationEnd;
import org.restlet.ext.odata.internal.edm.EntityType;
import org.restlet.ext.odata.internal.edm.Metadata;
import org.restlet.ext.odata.internal.edm.Property;
import org.restlet.ext.odata.internal.edm.Type;
import org.restlet.ext.odata.internal.reflect.ReflectUtils;
import org.restlet.ext.xml.DomRepresentation;
import org.restlet.ext.xml.SaxRepresentation;
import org.restlet.ext.xml.XmlWriter;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.ResourceException;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

/**
 * Acts as a manager for a specific remote WCF Data Service. WCF Data Services
 * are stateless, but the Service is not. State on the client is maintained
 * between interactions in order to support features such as update management.<br>
 * <br>
 * This Java class is more or less equivalent to the WCF DataServiceContext
 * class.
 * 
 * @author Jerome Louvel
 * @see <a
 *      href="http://msdn.microsoft.com/en-us/library/system.data.services.client.dataservicecontext.aspx">DataServiceContext
 *      Class on MSDN</a>
 */
public class Service {
    /** WCF data services metadata namespace. */
    public final static String WCF_DATASERVICES_METADATA_NAMESPACE = "http://schemas.microsoft.com/ado/2007/08/dataservices/metadata";

    /** WCF data services namespace. */
    public final static String WCF_DATASERVICES_NAMESPACE = "http://schemas.microsoft.com/ado/2007/08/dataservices";

    /** WCF data services scheme namespace. */
    public final static String WCF_DATASERVICES_SCHEME_NAMESPACE = "http://schemas.microsoft.com/ado/2007/08/dataservices/scheme";

    /** The credentials used to authenticate requests. */
    private ChallengeResponse credentials;

    /**
     * The version of the OData protocol extensions defined in every request
     * issued by this service.
     */
    private String dataServiceVersion;

    /** The latest request sent to the service. */
    private Request latestRequest;

    /** The response to the latest request. */
    private Response latestResponse;

    /** The internal logger. */
    private Logger logger;

    /**
     * The maximum version of the OData protocol extensions the client can
     * accept in a response.
     */
    private String maxDataServiceVersion;

    /** The metadata of the WCF service. */
    private Metadata metadata;

    /** The reference of the WCF service. */
    private Reference serviceRef;

    /**
     * Constructor.
     * 
     * @param serviceRef
     *            The reference to the WCF service.
     */
    public Service(Reference serviceRef) {
        this.serviceRef = serviceRef;
    }

    /**
     * Constructor.
     * 
     * @param serviceUri
     *            The URI of the WCF service.
     */
    public Service(String serviceUri) {
        this(new Reference(serviceUri));
    }

    /**
     * Adds an entity to an entity set.
     * 
     * @param entitySetName
     *            The path of the entity set relatively to the service URI.
     * @param entity
     *            The entity to put.
     * @return The reference of the newly created entity.
     * @throws Exception
     */
    public void addEntity(String entitySetName, Object entity) throws Exception {
        Entry entry = new Entry();
        entry.setContent(toContent(entity));

        ClientResource resource = createResource(entitySetName);

        try {
            // TODO Fix chunked request with net client connector
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            entry.write(o);
            StringRepresentation r = new StringRepresentation(o.toString(),
                    MediaType.APPLICATION_ATOM);
            Representation rep = resource.post(r);
            EntryContentHandler<?> entryContentHandler = new EntryContentHandler<Object>(
                    entity.getClass(), (Metadata) getMetadata(), getLogger());
            Feed feed = new Feed();
            feed.getEntries().add(new Entry(rep, entryContentHandler));
        } catch (ResourceException re) {
            throw new ResourceException(re.getStatus(),
                    "Can't add entity to this entity set "
                            + resource.getReference());
        } finally {
            this.latestRequest = resource.getRequest();
            this.latestResponse = resource.getResponse();
        }
    }

    /**
     * Adds an association between the source and the target entity via the
     * given property name.
     * 
     * @param source
     *            The source entity to update.
     * @param sourceProperty
     *            The name of the property of the source entity.
     * @param target
     *            The entity to add to the source entity.
     * @throws Exception
     */
    public void addLink(Object source, String sourceProperty, Object target)
            throws Exception {
        if (getMetadata() == null || source == null) {
            return;
        }
        if (target != null) {
            addEntity(getSubpath(source, sourceProperty), target);
        }
    }

    /**
     * Sets the association between the source and the target entity via the
     * given property name. If target is set to null, the call represents a
     * delete link operation.
     * 
     * @param source
     *            The source entity to update.
     * @param sourceProperty
     *            The name of the property of the source entity.
     * @param target
     *            The entity to add to the source entity.
     * @throws Exception
     */
    public void setLink(Object source, String sourceProperty, Object target)
            throws Exception {
        if (getMetadata() == null || source == null) {
            return;
        }
        if (target != null) {
            // TODO Take into acount the case where the target does exist.
            Metadata metadata = (Metadata) getMetadata();
            ClientResource resource = createResource(metadata
                    .getSubpath(source)
                    + "/$links/" + sourceProperty);

            try {
                // TODO Fix chunked request with net client connector
                StringBuilder sb = new StringBuilder("<uri xmlns=\"");
                sb.append(WCF_DATASERVICES_NAMESPACE);
                sb.append("\">");
                sb.append(serviceRef.toString());
                sb.append(metadata.getSubpath(target));
                sb.append("</uri>");

                StringRepresentation r = new StringRepresentation(
                        sb.toString(), MediaType.APPLICATION_XML);
                resource.put(r);
            } catch (ResourceException re) {
                throw new ResourceException(re.getStatus(),
                        "Can't set entity to this entity set "
                                + resource.getReference());
            } finally {
                this.latestRequest = resource.getRequest();
                this.latestResponse = resource.getResponse();
            }
        } else {
            ReflectUtils.invokeSetter(source, sourceProperty, null);
            updateEntity(source);
        }
    }

    /**
     * Creates a query to a specific entity hosted by this service.
     * 
     * @param <T>
     *            The class of the target entity.
     * @param subpath
     *            The path to this entity relatively to the service URI.
     * @param entityClass
     *            The target class of the entity.
     * @return A query object.
     */
    public <T> Query<T> createQuery(String subpath, Class<T> entityClass) {
        return new Query<T>(this, subpath, entityClass);
    }

    /**
     * Returns an instance of {@link ClientResource} given an absolute
     * reference. This resource is completed with the service credentials. This
     * method can be overriden in order to complete the sent requests.
     * 
     * @param reference
     *            The reference of the target resource.
     * @return An instance of {@link ClientResource}.
     */
    public ClientResource createResource(Reference reference) {
        ClientResource resource = new ClientResource(reference);
        resource.setChallengeResponse(getCredentials());

        if (getDataServiceVersion() != null
                || getMaxDataServiceVersion() != null) {
            Form form = new Form();
            if (getDataServiceVersion() != null) {
                form.add("DataServiceVersion", getDataServiceVersion());
            }
            if (getMaxDataServiceVersion() != null) {
                form.add("MaxDataServiceVersion", getMaxDataServiceVersion());
            }
            resource.getRequestAttributes().put(
                    HeaderConstants.ATTRIBUTE_HEADERS, form);
        }

        return resource;
    }

    /**
     * Returns an instance of {@link ClientResource} given a path (relative to
     * the service reference). This resource is completed with the service
     * credentials. This method can be overriden in order to complete the sent
     * requests.
     * 
     * @param relativePath
     *            The relative reference of the target resource.
     * @return An instance of {@link ClientResource} given a path (relative to
     *         the service reference).
     */
    public ClientResource createResource(String relativePath) {
        String ref = serviceRef.toString();
        if (ref.endsWith("/")) {
            if (relativePath.startsWith("/")) {
                ref = ref + relativePath.substring(1);
            } else {
                ref = ref + relativePath;
            }
        } else {
            if (relativePath.startsWith("/")) {
                ref = ref + relativePath;
            } else {
                ref = ref + "/" + relativePath;
            }
        }
        return createResource(new Reference(ref));
    }

    /**
     * Deletes an entity.
     * 
     * @param entity
     *            The entity to delete
     * @throws ResourceException
     */
    public void deleteEntity(Object entity) throws ResourceException {
        if (getMetadata() == null) {
            return;
        }

        ClientResource resource = createResource(getSubpath(entity));

        try {
            resource.delete();
        } catch (ResourceException re) {
            throw new ResourceException(re.getStatus(),
                    "Can't delete this entity " + resource.getReference());
        } finally {
            this.latestRequest = resource.getRequest();
            this.latestResponse = resource.getResponse();
        }
    }

    /**
     * Deletes an entity.
     * 
     * @param entitySubpath
     *            The path of the entity to delete
     * @throws ResourceException
     */
    public void deleteEntity(String entitySubpath) throws ResourceException {
        ClientResource resource = createResource(entitySubpath);

        try {
            resource.delete();
        } catch (ResourceException re) {
            throw new ResourceException(re.getStatus(),
                    "Can't delete this entity " + resource.getReference());
        } finally {
            this.latestRequest = resource.getRequest();
            this.latestResponse = resource.getResponse();
        }
    }

    /**
     * Removes the association between a source entity and a target entity via
     * the given property name.
     * 
     * @param source
     *            The source entity to update.
     * @param sourceProperty
     *            The name of the property of the source entity.
     * @param target
     *            The entity to delete from the source entity.
     * @throws ResourceException
     */
    public void deleteLink(Object source, String sourceProperty, Object target)
            throws ResourceException {
        if (getMetadata() == null) {
            return;
        }
        deleteEntity(getSubpath(source, sourceProperty, target));
    }

    /**
     * Returns the credentials used to authenticate requests.
     * 
     * @return The credentials used to authenticate requests.
     */
    public ChallengeResponse getCredentials() {
        return credentials;
    }

    /**
     * Returns the version of the OData protocol extensions defined in every
     * request issued by this service.
     * 
     * @return The version of the OData protocol extensions defined in every
     *         request issued by this service.
     */
    public String getDataServiceVersion() {
        return dataServiceVersion;
    }

    /**
     * Returns the ETag value for the given entity.
     * 
     * @param entity
     *            The given entity.
     * @return The ETag value for the given entity.
     */
    private String getETag(Object entity) {
        String result = null;
        if (entity != null) {
            Metadata metadata = (Metadata) getMetadata();
            EntityType type = metadata.getEntityType(entity.getClass());

            StringBuilder sb = new StringBuilder();
            boolean found = false;
            for (Property property : type.getProperties()) {
                if (property.isConcurrent()) {
                    found = true;
                    Object value = null;
                    try {
                        value = ReflectUtils.invokeGetter(entity, property
                                .getName());
                        if (value != null) {
                            sb.append(value);
                        }
                    } catch (Exception e) {
                        getLogger().warning(
                                "Cannot get the value of the property "
                                        + property.getName() + " on " + entity);
                    }
                }
            }

            if (found) {
                result = Reference.encode(sb.toString(), CharacterSet.US_ASCII);
            }
        }

        return result;
    }

    /**
     * Returns the latest request sent to the service.
     * 
     * @return The latest request sent to the service.
     */
    public Request getLatestRequest() {
        return latestRequest;
    }

    /**
     * Returns the response to the latest request.
     * 
     * @return The response to the latest request.
     */
    public Response getLatestResponse() {
        return latestResponse;
    }

    /**
     * Returns the current logger.
     * 
     * @return The current logger.
     */
    private Logger getLogger() {
        if (logger == null) {
            logger = Context.getCurrentLogger();
        }
        return logger;
    }

    /**
     * Returns the maximum version of the OData protocol extensions the client
     * can accept in a response.
     * 
     * @return The maximum version of the OData protocol extensions the client
     *         can accept in a response.
     */
    public String getMaxDataServiceVersion() {
        return maxDataServiceVersion;
    }

    /**
     * Returns the metadata document related to the current service.
     * 
     * @return The metadata document related to the current service.
     */
    protected Object getMetadata() {
        if (metadata == null) {
            ClientResource resource = createResource("$metadata");

            try {
                getLogger().log(
                        Level.INFO,
                        "Get the metadata for " + serviceRef + " at "
                                + resource.getReference());
                Representation rep = resource.get(MediaType.APPLICATION_XML);
                this.metadata = new Metadata(rep, resource.getReference());
            } catch (ResourceException e) {
                getLogger().log(
                        Level.SEVERE,
                        "Can't get the metadata for " + serviceRef
                                + " (response's status: "
                                + resource.getStatus() + ")");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE,
                        "Can't get the metadata for " + serviceRef, e);
            }
        }

        return metadata;
    }

    /**
     * Returns the reference to the WCF service.
     * 
     * @return The reference to the WCF service.
     */
    public Reference getServiceRef() {
        return serviceRef;
    }

    /**
     * According to the metadata of the service, returns the path of the given
     * entity relatively to the current WCF service.
     * 
     * @param entity
     *            The entity.
     * @return The path of the given entity relatively to the current WCF
     *         service.
     */
    private String getSubpath(Object entity) {
        return ((Metadata) getMetadata()).getSubpath(entity);
    }

    /**
     * According to the metadata of the service, returns the path of the given
     * entity's property relatively to the current WCF service.
     * 
     * @param entity
     *            The entity.
     * @param propertyName
     *            The name of the property.
     * @return The path of the given entity's property relatively to the current
     *         WCF service.
     */
    private String getSubpath(Object entity, String propertyName) {
        return ((Metadata) getMetadata()).getSubpath(entity, propertyName);
    }

    /**
     * According to the metadata of the service, returns the relative path of
     * the given target entity linked to the source entity via the source
     * property.
     * 
     * @param source
     *            The source entity to update.
     * @param sourceProperty
     *            The name of the property of the source entity.
     * @param target
     *            The entity linked to the source entity.
     * @return
     */
    private String getSubpath(Object source, String sourceProperty,
            Object target) {
        return ((Metadata) getMetadata()).getSubpath(source, sourceProperty,
                target);
    }

    /**
     * Returns the binary representation of the given media resource. If the
     * entity is not a media resource, it returns null.
     * 
     * @param entity
     *            The given media resource.
     * @return The binary representation of the given media resource.
     */
    public Representation getValue(Object entity) throws ResourceException {
        Reference ref = getValueRef(entity);
        if (ref != null) {
            ClientResource cr = createResource(ref);
            return cr.get();
        }

        return null;
    }

    /**
     * Returns the binary representation of the given media resource. If the
     * entity is not a media resource, it returns null.
     * 
     * @param entity
     *            The given media resource.
     * @param acceptedMediaTypes
     *            The requested media types of the representation.
     * @return The given media resource.
     */
    public Representation getValue(Object entity,
            List<Preference<MediaType>> acceptedMediaTypes)
            throws ResourceException {
        Reference ref = getValueRef(entity);
        if (ref != null) {
            ClientResource cr = createResource(ref);
            cr.getClientInfo().setAcceptedMediaTypes(acceptedMediaTypes);
            return cr.get();
        }

        return null;
    }

    /**
     * Returns the binary representation of the given media resource. If the
     * entity is not a media resource, it returns null.
     * 
     * @param entity
     *            The given media resource.
     * @param mediaType
     *            The requested media type of the representation
     * @return The given media resource.
     */
    public Representation getValue(Object entity, MediaType mediaType)
            throws ResourceException {
        Reference ref = getValueRef(entity);
        if (ref != null) {
            ClientResource cr = createResource(ref);
            return cr.get(mediaType);
        }

        return null;
    }

    /**
     * Returns the reference of the binary representation of the given entity,
     * if this is a media resource. It returns null otherwise.
     * 
     * @param entity
     *            The media resource.
     * @return The reference of the binary representation of the given entity,
     *         if this is a media resource. It returns null otherwise.
     */
    public Reference getValueRef(Object entity) {
        if (entity != null) {
            Metadata metadata = (Metadata) getMetadata();
            EntityType type = metadata.getEntityType(entity.getClass());
            if (type.isBlob() && type.getBlobValueRefProperty() != null) {
                try {
                    return (Reference) ReflectUtils.invokeGetter(entity, type
                            .getBlobValueRefProperty().getName());
                } catch (Exception e) {
                    getLogger().warning(
                            "Cannot get the value of the property "
                                    + type.getBlobValueRefProperty().getName()
                                    + " on " + entity);
                }
            } else {
                getLogger().warning(
                        "This entity is not a media resource " + entity);
            }
        }

        return null;
    }

    /**
     * Updates the given entity object with the value of the specified property.
     * 
     * @param entity
     *            The entity to update.
     * @param propertyName
     *            The name of the property.
     */
    public void loadProperty(Object entity, String propertyName) {
        if (getMetadata() == null || entity == null) {
            return;
        }

        Metadata metadata = (Metadata) getMetadata();

        EntityType type = metadata.getEntityType(entity.getClass());
        AssociationEnd association = metadata
                .getAssociation(type, propertyName);

        if (association != null) {
            EntityType propertyEntityType = association.getType();
            try {
                Class<?> propertyClass = ReflectUtils.getSimpleClass(entity,
                        propertyName);
                if (propertyClass == null) {
                    propertyClass = Type.getJavaClass(propertyEntityType);
                }
                Iterator<?> iterator = createQuery(
                        getSubpath(entity, propertyName), propertyClass)
                        .iterator();

                ReflectUtils.setProperty(entity, propertyName, association
                        .isToMany(), iterator, propertyClass);
            } catch (Exception e) {
                getLogger().log(
                        Level.WARNING,
                        "Can't set the property " + propertyName + " of "
                                + entity.getClass() + " for the service"
                                + serviceRef, e);
            }
        } else {
            ClientResource resource = createResource(getSubpath(entity,
                    propertyName));
            try {
                Representation rep = resource.get();

                DomRepresentation xmlRep = new DomRepresentation(rep);
                // [ifndef android] instruction
                Node node = xmlRep.getNode("//" + propertyName);

                // [ifdef android] uncomment
                // Node node = null;
                // try {
                // org.w3c.dom.NodeList nl = xmlRep.getDocument()
                // .getElementsByTagName(propertyName);
                // node = (nl.getLength() > 0) ? nl.item(0) : null;
                // } catch (IOException e1) {
                // }
                // [enddef]

                if (node != null) {
                    Property property = metadata.getProperty(entity,
                            propertyName);
                    try {
                        // [ifndef android] instruction
                        ReflectUtils.setProperty(entity, property, node
                                .getTextContent());
                        // [ifdef android] instruction uncomment
                        // ReflectUtils.setProperty(entity, property,
                        // org.restlet.ext.xml.XmlRepresentation.getTextContent(node));
                    } catch (Exception e) {
                        getLogger().log(
                                Level.WARNING,
                                "Can't set the property " + propertyName
                                        + " of " + entity.getClass()
                                        + " for the service" + serviceRef, e);
                    }
                }
            } catch (ResourceException e) {
                getLogger().log(
                        Level.WARNING,
                        "Can't get the following resource "
                                + resource.getReference() + " for the service"
                                + serviceRef, e);
            }
        }
    }

    /**
     * Sets the credentials used to authenticate requests.
     * 
     * @param credentials
     *            The credentials used to authenticate requests.
     */
    public void setCredentials(ChallengeResponse credentials) {
        this.credentials = credentials;
    }

    /**
     * Sets the version of the OData protocol extensions defined in every
     * request issued by this service.
     * 
     * @param dataServiceVersion
     *            The version of the OData protocol extensions defined in every
     *            request issued by this service.
     */
    public void setDataServiceVersion(String dataServiceVersion) {
        this.dataServiceVersion = dataServiceVersion;
    }

    /**
     * Sets the latest request sent to the service.
     * 
     * @param latestRequest
     *            The latest request sent to the service.
     */
    public void setLatestRequest(Request latestRequest) {
        this.latestRequest = latestRequest;
    }

    /**
     * Sets the response to the latest request.
     * 
     * @param latestResponse
     *            The response to the latest request.
     */
    public void setLatestResponse(Response latestResponse) {
        this.latestResponse = latestResponse;
    }

    /**
     * Sets the maximum version of the OData protocol extensions the client can
     * accept in a response.
     * 
     * @param maxDataServiceVersion
     *            The maximum version of the OData protocol extensions the
     *            client can accept in a response.
     */
    public void setMaxDataServiceVersion(String maxDataServiceVersion) {
        this.maxDataServiceVersion = maxDataServiceVersion;
    }

    /**
     * Converts an entity to an Atom content object.
     * 
     * @param entity
     *            The entity to wrap.
     * @return The Atom content object that corresponds to the given entity.
     */
    private Content toContent(final Object entity) throws Exception {
        Representation r = new SaxRepresentation(MediaType.APPLICATION_XML) {
            @Override
            public void write(XmlWriter writer) throws IOException {
                try {
                    // Attribute for nullable values.
                    AttributesImpl nullAttrs = new AttributesImpl();
                    nullAttrs.addAttribute(WCF_DATASERVICES_METADATA_NAMESPACE,
                            "null", null, "boolean", "true");

                    writer
                            .forceNSDecl(WCF_DATASERVICES_METADATA_NAMESPACE,
                                    "m");
                    writer.forceNSDecl(WCF_DATASERVICES_NAMESPACE, "d");
                    writer.startElement(WCF_DATASERVICES_METADATA_NAMESPACE,
                            "properties");

                    for (Field field : entity.getClass().getDeclaredFields()) {
                        String getter = "get"
                                + field.getName().substring(0, 1).toUpperCase()
                                + field.getName().substring(1);
                        Property prop = ((Metadata) getMetadata()).getProperty(
                                entity, field.getName());
                        if (prop != null) {
                            for (Method method : entity.getClass()
                                    .getDeclaredMethods()) {
                                if (method.getReturnType() != null
                                        && getter.equals(method.getName())
                                        && method.getParameterTypes().length == 0) {
                                    Object value = null;
                                    try {
                                        value = method.invoke(entity,
                                                (Object[]) null);
                                    } catch (Exception e) {

                                    }
                                    if (value != null) {
                                        writer.startElement(
                                                WCF_DATASERVICES_NAMESPACE,
                                                prop.getName());
                                        writer.characters(Type.toEdm(value,
                                                prop.getType()));
                                        writer.endElement(
                                                WCF_DATASERVICES_NAMESPACE,
                                                prop.getName());
                                    } else {
                                        if (prop.isNullable()) {
                                            writer.emptyElement(
                                                    WCF_DATASERVICES_NAMESPACE,
                                                    prop.getName(), prop
                                                            .getName(),
                                                    nullAttrs);
                                        } else {
                                            getLogger().warning(
                                                    "The following property has a null value but is not marked as nullable: "
                                                            + prop.getName());
                                            writer.emptyElement(
                                                    WCF_DATASERVICES_NAMESPACE,
                                                    prop.getName());
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    writer.endElement(WCF_DATASERVICES_METADATA_NAMESPACE,
                            "properties");
                } catch (SAXException e) {
                    throw new IOException(e.getMessage());
                }
            }
        };
        Content content = new Content();
        content.setInlineContent(r);
        content.setToEncode(false);
        return content;
    }

    /**
     * Updates an entity.
     * 
     * @param entity
     *            The entity to put.
     * @throws Exception
     */
    public void updateEntity(Object entity) throws Exception {
        if (getMetadata() == null) {
            return;
        }
        Entry entry = new Entry();
        entry.setContent(toContent(entity));

        ClientResource resource = createResource(getSubpath(entity));

        try {
            // TODO Fix chunked request with net client connector
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            entry.write(o);
            StringRepresentation r = new StringRepresentation(o.toString(),
                    MediaType.APPLICATION_ATOM);
            String eTag = getETag(entity);
            if (eTag != null) {
                // Add a condition
                resource.getConditions().setMatch(Arrays.asList(new Tag(eTag)));
            }
            resource.put(r);
        } catch (ResourceException re) {
            throw new ResourceException(re.getStatus(),
                    "Can't update this entity " + resource.getReference());
        } finally {
            this.latestRequest = resource.getRequest();
            this.latestResponse = resource.getResponse();
        }
    }

}