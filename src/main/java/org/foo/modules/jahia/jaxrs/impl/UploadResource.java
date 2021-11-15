package org.foo.modules.jahia.jaxrs.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.foo.modules.jahia.jaxrs.api.FileInfo;
import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.jahia.osgi.BundleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

@Path("/upload-file")
@Produces({MediaType.APPLICATION_JSON})
public class UploadResource {
    private static final Logger logger = LoggerFactory.getLogger(UploadResource.class);

    private static final String CONTENT_RANGE = "Content-Range";

    @POST
    @Consumes({MediaType.MULTIPART_FORM_DATA})
    public Response uploadFile(@Context HttpServletRequest httpServletRequest) {
        String contentRange = httpServletRequest.getHeader(CONTENT_RANGE);
        long fileFullLength = -1;
        long chunkFrom = -1;
        long chunkTo = -1;
        if (contentRange != null) {
            if (!contentRange.startsWith("bytes ")) {
                return Response.serverError().entity("Unexpected range format: " + contentRange).build();
            }
            String[] fromToAndLength = contentRange.substring(6).split(Pattern.quote("/"));
            fileFullLength = Long.parseLong(fromToAndLength[1]);
            String[] fromAndTo = fromToAndLength[0].split(Pattern.quote("-"));
            chunkFrom = Long.parseLong(fromAndTo[0]);
            chunkTo = Long.parseLong(fromAndTo[1]);
        }

        UploadServiceRegistrator uploadServiceRegistrator = BundleUtils.getOsgiService(UploadServiceRegistrator.class, null);
        if (uploadServiceRegistrator != null) {
            List<UploadService> uploadServices = uploadServiceRegistrator.getUploadServices();
            if (!uploadServices.isEmpty()) {
                try {
                    File tempDir = new File(System.getProperty("java.io.tmpdir"), httpServletRequest.getSession().getId());
                    List<FileItem> items = new ServletFileUpload(new DiskFileItemFactory(10000000, tempDir)).parseRequest(httpServletRequest);
                    Iterator<FileItem> it = items.iterator();
                    List<FileInfo> filesInfo = new ArrayList<>();
                    FileInfo fileInfo;
                    Map<String, String> formData = new HashMap<>();
                    while (it.hasNext()) {
                        FileItem item = it.next();
                        if (item.isFormField()) {
                            formData.put(item.getFieldName(), item.getString());
                        } else {
                            fileInfo = new FileInfo(item.getName(), item.getContentType());
                            writeFile(tempDir, item, fileInfo, fileFullLength, chunkFrom, chunkTo);
                            filesInfo.add(fileInfo);
                        }
                    }
                    filesInfo.forEach(fi -> {
                        fi.setFormData(formData);
                        if (fi.isComplete()) {
                            boolean remove = true;
                            for (UploadService uploadService : uploadServiceRegistrator.getUploadServices()) {
                                if (uploadService.checkFileExtension(fi)) {
                                    remove &= uploadService.uploadFile(fi);
                                }
                            }
                            if (remove) {
                                FileUtils.deleteQuietly(new File(fi.getServerPath()).getParentFile());
                            }
                        }
                    });
                    return Response.status(Response.Status.OK).entity(new ObjectMapper().writeValueAsString(filesInfo)).type(MediaType.APPLICATION_JSON).build();
                } catch (Exception e) {
                    logger.error("", e);
                    return Response.serverError().build();
                }
            }
        }
        return Response.ok().build();
    }

    private void writeFile(File tempDir, FileItem fileItem, FileInfo fileInfo, long fileFullLength, long chunkFrom, long chunkTo) throws Exception {
        if (!tempDir.exists()) {
            if (!tempDir.mkdir()) {
                throw new FileNotFoundException(tempDir.getAbsolutePath());
            }
        }
        File dir = new File(tempDir, fileItem.getName());
        if (!dir.exists()) {
            if (!dir.mkdir()) {
                throw new FileNotFoundException(dir.getAbsolutePath());
            }
        }
        File assembledFile = null;
        if (fileFullLength < 0) {  // File is not chunked
            assembledFile = new File(dir, fileItem.getName());
            fileInfo.setSize(fileItem.getSize());
            fileItem.write(assembledFile);
        } else {  // File is chunked
            byte[] bytes = fileItem.get();
            if (chunkFrom + bytes.length != chunkTo + 1) {
                throw new ServletException("Unexpected length of chunk: " + bytes.length + " != " + (chunkTo + 1) + " - " + chunkFrom);
            }
            saveChunk(dir, fileItem.getName(), chunkFrom, bytes);
            TreeMap<Long, Long> chunkStartsToLengths = getChunkStartsToLengths(dir, fileItem.getName());
            long lengthSoFar = getCommonLength(chunkStartsToLengths);
            fileInfo.setSize(lengthSoFar);
            if (lengthSoFar == fileFullLength) {
                assembledFile = assembleAndDeleteChunks(dir, fileItem.getName(), new ArrayList<>(chunkStartsToLengths.keySet()));
            }
        }
        if (assembledFile != null) {
            fileInfo.setComplete(true);
            fileInfo.setServerPath(assembledFile.getAbsolutePath());
        }
    }

    private static void saveChunk(File dir, String fileName, long from, byte[] bytes) throws IOException {
        File target = new File(dir, fileName + "." + from + ".chunk");
        try (OutputStream os = new FileOutputStream(target)) {
            os.write(bytes);
        }
    }

    private static TreeMap<Long, Long> getChunkStartsToLengths(File dir, String fileName) {
        TreeMap<Long, Long> chunkStartsToLengths = new TreeMap<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String chunkFileName = f.getName();
                if (chunkFileName.startsWith(fileName + ".") && chunkFileName.endsWith(".chunk")) {
                    chunkStartsToLengths.put(Long.parseLong(chunkFileName.substring(fileName.length() + 1, chunkFileName.length() - 6)), f.length());
                }
            }
        }
        return chunkStartsToLengths;
    }

    private static long getCommonLength(TreeMap<Long, Long> chunkStartsToLengths) {
        long ret = 0;
        for (long len : chunkStartsToLengths.values()) {
            ret += len;
        }
        return ret;
    }

    private static File assembleAndDeleteChunks(File dir, String fileName, List<Long> chunkStarts) throws IOException {
        File assembledFile = new File(dir, fileName);
        if (assembledFile.exists()) {
            return assembledFile;
        }
        try (OutputStream assembledOs = new FileOutputStream(assembledFile)) {
            byte[] buf = new byte[100000];
            for (long chunkFrom : chunkStarts) {
                File chunkFile = new File(dir, fileName + "." + chunkFrom + ".chunk");
                try (InputStream is = new FileInputStream(chunkFile)) {
                    while (true) {
                        int r = is.read(buf);
                        if (r == -1) {
                            break;
                        }
                        if (r > 0) {
                            assembledOs.write(buf, 0, r);
                        }
                    }
                }
                FileUtils.deleteQuietly(chunkFile);
            }
        }
        return assembledFile;
    }
}
