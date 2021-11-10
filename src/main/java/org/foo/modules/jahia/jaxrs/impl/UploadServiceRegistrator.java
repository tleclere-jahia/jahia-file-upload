package org.foo.modules.jahia.jaxrs.impl;

import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.jahia.osgi.BundleUtils;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(service = UploadServiceRegistrator.class, immediate = true)
public class UploadServiceRegistrator {
    public static final Logger logger = LoggerFactory.getLogger(UploadServiceRegistrator.class);

    private final List<UploadService> uploadServices;

    public UploadServiceRegistrator() {
        uploadServices = new CopyOnWriteArrayList<>();
    }

    @Reference(service = UploadService.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "removeUploadService")
    public void addUploadService(UploadService uploadService) {
        final Bundle bundle = FrameworkUtil.getBundle(uploadService.getClass());
        final String module = BundleUtils.getDisplayName(bundle);
        uploadServices.add(uploadService);
        logger.info(String.format("Registered an upload service %s provided by %s", uploadService, module));
    }

    public void removeUploadService(UploadService uploadService) {
        final Bundle bundle = FrameworkUtil.getBundle(uploadService.getClass());
        final String module = BundleUtils.getDisplayName(bundle);
        if (uploadServices.remove(uploadService)) {
            logger.info(String.format("Unregistered an upload service %s provided by %s", uploadService, module));
        } else {
            logger.error(String.format("Failed to unregister an upload service %s provided by %s", uploadService, module));
        }
    }

    public List<UploadService> getUploadServices() {
        return Collections.unmodifiableList(uploadServices);
    }
}
