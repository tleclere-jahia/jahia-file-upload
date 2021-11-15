package org.foo.modules.jahia.jaxrs.api;

public interface UploadService {
    boolean checkFileExtension(FileInfo fileInfo);

    /**
     * Execute some custom code with the assembled file before deleting the folder
     * @param fileInfo the file info
     * @return true if the file must be removed, false otherwise
     */
    boolean uploadFile(FileInfo fileInfo);
}
