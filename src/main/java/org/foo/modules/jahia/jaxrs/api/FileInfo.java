package org.foo.modules.jahia.jaxrs.api;

import java.util.Collections;
import java.util.Map;

public class FileInfo {
    private String name;
    private String type;
    private long size;
    private boolean complete;
    private final Map<String, String> formData;

    public FileInfo(Map<String, String> formData) {
        this.formData = formData;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public Map<String, String> getFormData() {
        return Collections.unmodifiableMap(formData);
    }
}
