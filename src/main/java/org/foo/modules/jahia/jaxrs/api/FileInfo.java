package org.foo.modules.jahia.jaxrs.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FileInfo {
    private final String name;
    private final String type;
    private long size;
    private boolean complete;
    private String serverPath;
    private Map<String, String> formData;

    public FileInfo(String name, String type) {
        this.name = name;
        this.type = type;
        this.formData = new HashMap<>();
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    public String getServerPath() {
        return serverPath;
    }

    public void setServerPath(String serverPath) {
        this.serverPath = serverPath;
    }

    public Map<String, String> getFormData() {
        return Collections.unmodifiableMap(formData);
    }

    public void setFormData(Map<String, String> formData) {
        this.formData = formData;
    }
}
