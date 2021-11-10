package org.foo.modules.jahia.jaxrs.config;

import org.foo.modules.jahia.jaxrs.impl.UploadResource;
import org.glassfish.jersey.server.ResourceConfig;

public class JaxRsConfig extends ResourceConfig {
    public JaxRsConfig() {
        super(UploadResource.class);
    }
}
