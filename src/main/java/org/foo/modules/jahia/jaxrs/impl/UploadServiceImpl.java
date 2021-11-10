package org.foo.modules.jahia.jaxrs.impl;

import org.foo.modules.jahia.jaxrs.api.FileInfo;
import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = UploadService.class, immediate = true)
public class UploadServiceImpl implements UploadService {
    private static final Logger logger = LoggerFactory.getLogger(UploadServiceImpl.class);

    @Override
    public boolean checkFileExtension(FileInfo fileInfo) {
        logger.info("Check file extension: {}", fileInfo);
        return true;
    }

    @Override
    public void uploadFile(FileInfo fileInfo) {
        logger.info("Upload jax-rs: {}", fileInfo);
    }
}
