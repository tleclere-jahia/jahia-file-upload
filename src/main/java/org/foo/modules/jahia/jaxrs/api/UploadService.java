package org.foo.modules.jahia.jaxrs.api;

public interface UploadService {
    boolean checkFileExtension(String fileName);

    /**
     * Execute some custom code with the assembled file before deleting the folder
     * @param fileInfo the file info
     */
    void uploadFile(FileInfo fileInfo);
}
