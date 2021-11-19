package org.foo.modules.jahia.jaxrs.impl;

import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.jahia.api.Constants;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component(service = UploadServiceRegistrator.class, immediate = true)
public class UploadServiceRegistrator {
    private static final Logger logger = LoggerFactory.getLogger(UploadServiceRegistrator.class);

    public static final String CONFIGURATION_JCRFOLDER_PATH = "jcrFolderPath";

    private final List<UploadService> uploadServices;
    private JCRTemplate jcrTemplate;
    private String folderNodePath;

    public UploadServiceRegistrator() {
        uploadServices = new CopyOnWriteArrayList<>();
    }

    @Activate
    private void onActivate(Map<String, ?> configuration) throws BundleException {
        if (configuration.containsKey(CONFIGURATION_JCRFOLDER_PATH)) {
            try {
                folderNodePath = jcrTemplate.doExecuteWithSystemSession(systemSession -> {
                    if (!systemSession.nodeExists((String) configuration.get(CONFIGURATION_JCRFOLDER_PATH))) {
                        return null;
                    }
                    JCRNodeWrapper rootNode = systemSession.getNode((String) configuration.get(CONFIGURATION_JCRFOLDER_PATH));
                    if (!rootNode.hasNode(UploadService.ROOT_FOLDER)) {
                        rootNode.addNode(UploadService.ROOT_FOLDER, Constants.JAHIANT_FOLDER);
                        rootNode.saveSession();
                    }
                    return rootNode.getNode(UploadService.ROOT_FOLDER).getPath();
                });
            } catch (RepositoryException e) {
                logger.error("", e);
            }
        }
    }

    @Reference
    private void setJcrTemplate(JCRTemplate jcrTemplate) {
        this.jcrTemplate = jcrTemplate;
    }

    @Reference(service = UploadService.class, cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC, unbind = "removeUploadService")
    private void addUploadService(UploadService uploadService) {
        final Bundle bundle = FrameworkUtil.getBundle(uploadService.getClass());
        final String module = BundleUtils.getDisplayName(bundle);
        uploadServices.add(uploadService);
        logger.info(String.format("Registered an upload service %s provided by %s", uploadService, module));
    }

    private void removeUploadService(UploadService uploadService) {
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

    public String getFolderNodePath() {
        return folderNodePath;
    }
}
