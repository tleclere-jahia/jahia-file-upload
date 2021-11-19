package org.foo.modules.jahia.jaxrs.api;

import org.jahia.services.content.JCRNodeWrapper;

import java.util.Map;

public interface UploadService {
    String ROOT_FOLDER = "file-upload";
    String FORM_DATA_FILE = "file";

    /***
     * @return property in the form data where to save temporary file
     */
    String getTempFolderPropertyInFormData();

    /***
     * Check if the file has the good extension
     * @param formData the form data
     * @return true if the extension is accepted, false otherwiser
     */
    boolean checkFileExtension(Map<String, String> formData);

    /**
     * Execute some custom code with the assembled file before deleting the folder
     *
     * @param fileInfo      the file info
     * @param assembledFile the assembled file as an external node
     * @return true if the file must be removed, false otherwise
     */
    boolean uploadFile(FileInfo fileInfo, JCRNodeWrapper assembledFile);
}
