package org.foo.modules.jahia.jaxrs.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.foo.modules.jahia.jaxrs.api.FileInfo;
import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.foo.modules.jahia.jaxrs.api.UploadServiceRegistrator;
import org.jahia.api.Constants;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Path("/upload-file")
@Produces({MediaType.APPLICATION_JSON})
public class UploadResource {
    private static final Logger logger = LoggerFactory.getLogger(UploadResource.class);

    private static final String CONTENT_RANGE = "Content-Range";
    private static final File ROOT_FOLER = new File(System.getProperty("java.io.tmpdir"), UploadService.ROOT_FOLDER);

    @POST
    @Consumes({MediaType.MULTIPART_FORM_DATA})
    public Response uploadFile(@Context HttpServletRequest httpServletRequest) {
        try {
            if (!ROOT_FOLER.exists()) {
                if (!ROOT_FOLER.mkdir()) {
                    return Response.serverError().entity(String.format("Unable to create temp folder: %s", ROOT_FOLER.getAbsolutePath())).build();
                }
            }

            FileInfo result = (FileInfo) JCRTemplate.getInstance().doExecuteWithSystemSession(systemSession -> {
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
                String folderNodePath = uploadServiceRegistrator.getFolderNodePath();
                if (folderNodePath != null && systemSession.nodeExists(folderNodePath)) {
                    List<UploadService> uploadServices = uploadServiceRegistrator.getUploadServices();
                    JCRNodeWrapper folderNode;
                    try {
                        Map<Boolean, List<FileItem>> fileItems = new ServletFileUpload(new DiskFileItemFactory(10000000, ROOT_FOLER))
                                .parseRequest(httpServletRequest).stream().collect(Collectors.groupingBy(FileItem::isFormField));
                        Map<String, Object> formData = new HashMap<>();
                        fileItems.get(true).forEach(item -> formData.put(item.getFieldName(), item.getString()));
                        if (fileFullLength >= 0) {
                            formData.put(UploadService.FORM_DATA_SIZE, fileFullLength);
                        } else if (!fileItems.get(false).isEmpty()) {
                            formData.put(UploadService.FORM_DATA_SIZE, fileItems.get(false).get(0).getSize());
                        }
                        if (!formData.containsKey(UploadService.FORM_DATA_FILE)) {
                            return Response.serverError().build();
                        }
                        if (!uploadServices.stream().map(service -> service.checkFileExtension(formData)).reduce(false, (a, b) -> a || b)) {
                            logger.warn("File {} not allowed", formData.get(UploadService.FORM_DATA_FILE));
                            return Response.serverError().build();
                        }

                        int nbFiles = fileItems.get(false).size();
                        if (nbFiles > 1) {
                            logger.warn("Unable to deal multipe ({}) files ; only the first is uploaded.", nbFiles);
                        }
                        FileInfo fileInfo = new FileInfo(formData);

                        // TODO: Deal multiple files
                        Optional<FileItem> op = fileItems.get(false).stream().findFirst();
                        if (op.isPresent()) {
                            FileItem item = op.get();
                            fileInfo.setName(item.getName());
                            fileInfo.setType(item.getContentType());
                            for (UploadService uploadService : uploadServices) {
                                if (uploadService.checkFileExtension(formData)) {
                                    folderNode = systemSession.getNode(folderNodePath);
                                    String getTempFolderPropertyInFormData = uploadService.getTempFolderPropertyInFormData();
                                    if (getTempFolderPropertyInFormData != null && formData.containsKey(getTempFolderPropertyInFormData)) {
                                        folderNode = createFolderFromPath(folderNode, ((String) formData.get(getTempFolderPropertyInFormData)).split("/"));
                                    }
                                    JCRNodeWrapper assembledFile = writeFile(folderNode, item, fileInfo, fileFullLength, chunkFrom, chunkTo);
                                    if (assembledFile != null) {
                                        fileInfo.setComplete(true);
                                        JCRNodeWrapper parentNode = assembledFile.getParent();
                                        if (uploadService.uploadFile(fileInfo, assembledFile)) {
                                            parentNode.remove();
                                        }
                                    }
                                }
                            }
                        }
                        systemSession.save();
                        return fileInfo;
                    } catch (Exception e) {
                        throw new RepositoryException(e);
                    }
                }
                return null;
            });
            return Response.status(Response.Status.OK).entity(new ObjectMapper().writeValueAsString(result)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            logger.error("", e);
            return Response.serverError().build();
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Long getFile(@QueryParam("path") String path, @Context HttpServletRequest httpServletRequest) {
        try {
            return JCRTemplate.getInstance().doExecuteWithSystemSession(systemSession -> {
                String folderNodePath = BundleUtils.getOsgiService(UploadServiceRegistrator.class, null).getFolderNodePath();
                if (folderNodePath == null) {
                    return 0L;
                }
                String jcrPath = folderNodePath;
                if (StringUtils.isNotBlank(path)) {
                    jcrPath += "/" + path;
                }
                if (!systemSession.nodeExists(jcrPath)) {
                    return 0L;
                }
                TreeMap<Long, Long> chunkStartsToLengths = getChunkStartsToLengths(systemSession.getNode(jcrPath), StringUtils.substringAfterLast(path, "/"));
                return getCommonLength(chunkStartsToLengths);
            });
        } catch (RepositoryException e) {
            logger.error("", e);
            return 0L;
        }
    }

    private static JCRNodeWrapper createFolderFromPath(JCRNodeWrapper folderNode, String... pathes) throws RepositoryException {
        for (String path : pathes) {
            if (!folderNode.hasNode(path)) {
                folderNode.addNode(path, Constants.JAHIANT_FOLDER);
            }
            folderNode = folderNode.getNode(path);
        }
        return folderNode;
    }

    private static JCRNodeWrapper writeFile(JCRNodeWrapper folderNode, FileItem fileItem, FileInfo fileInfo, long fileFullLength, long chunkFrom, long chunkTo) throws Exception {
        JCRNodeWrapper file = null;
        if (!folderNode.hasNode(fileItem.getName())) {
            folderNode.addNode(fileItem.getName(), Constants.JAHIANT_FOLDER);
        }
        folderNode = folderNode.getNode(fileItem.getName());

        File assembledFile;
        if (fileFullLength < 0) {  // File is not chunked
            assembledFile = new File(ROOT_FOLER, fileItem.getName());
            fileInfo.setSize(fileItem.getSize());
            fileItem.write(assembledFile);
            try (FileInputStream is = new FileInputStream(assembledFile)) {
                file = folderNode.uploadFile(fileItem.getName(), is, fileItem.getContentType());
                folderNode.saveSession();
            }
            FileUtils.deleteQuietly(assembledFile);
        } else {  // File is chunked
            byte[] bytes = fileItem.get();
            if (chunkFrom + bytes.length != chunkTo + 1) {
                throw new ServletException("Unexpected length of chunk: " + bytes.length + " != " + (chunkTo + 1) + " - " + chunkFrom);
            }
            saveChunk(folderNode, fileItem.getName(), fileItem.getContentType(), chunkFrom, bytes);
            TreeMap<Long, Long> chunkStartsToLengths = getChunkStartsToLengths(folderNode, fileItem.getName());
            long lengthSoFar = getCommonLength(chunkStartsToLengths);
            fileInfo.setSize(lengthSoFar);
            if (lengthSoFar == fileFullLength) {
                file = assembleAndDeleteChunks(folderNode, fileItem.getName(), fileItem.getContentType(), new ArrayList<>(chunkStartsToLengths.keySet()));
            }
        }
        return file;
    }

    private static JCRNodeWrapper uploadFile(JCRNodeWrapper folderNode, File assembledFile, String fileName, String fileType) throws RepositoryException {
        JCRNodeWrapper file = null;
        try (FileInputStream is = new FileInputStream(assembledFile)) {
            file = folderNode.uploadFile(fileName, is, fileType);
            file.saveSession();
        } catch (IOException e) {
            logger.error("", e);
        }
        return file;
    }

    private static void saveChunk(JCRNodeWrapper folderNode, String fileName, String fileType, long from, byte[] bytes) throws RepositoryException {
        try (ByteArrayInputStream is = new ByteArrayInputStream(bytes)) {
            JCRNodeWrapper file = folderNode.uploadFile(fileName + "." + from + ".chunk", is, fileType);
            file.saveSession();
        } catch (IOException e) {
            logger.error("", e);
        }
    }

    private static TreeMap<Long, Long> getChunkStartsToLengths(JCRNodeWrapper dir, String fileName) {
        TreeMap<Long, Long> chunkStartsToLengths = new TreeMap<>();
        try {
            JCRNodeIteratorWrapper it = dir.getNodes();
            while (it.hasNext()) {
                JCRFileNode fileNode = (JCRFileNode) it.nextNode();
                String chunkFileName = fileNode.getName();
                if (chunkFileName.startsWith(fileName + ".") && chunkFileName.endsWith(".chunk")) {
                    chunkStartsToLengths.put(Long.parseLong(chunkFileName.substring(fileName.length() + 1, chunkFileName.length() - 6)), fileNode.getFileContent().getContentLength());
                }
            }
        } catch (RepositoryException e) {
            logger.error("", e);
        }
        return chunkStartsToLengths;
    }

    private static Long getCommonLength(TreeMap<Long, Long> chunkStartsToLengths) {
        Long ret = 0L;
        for (Long len : chunkStartsToLengths.values()) {
            ret += len;
        }
        return ret;
    }

    private static JCRNodeWrapper assembleAndDeleteChunks(JCRNodeWrapper folderNode, String fileName, String fileType, List<Long> chunkStarts) throws IOException, RepositoryException {
        if (folderNode.hasNode(fileName)) {
            return folderNode.getNode(fileName);
        }
        File assembledFile = new File(ROOT_FOLER, fileName);
        try (FileOutputStream assembledOs = new FileOutputStream(assembledFile)) {
            byte[] buf = new byte[100000];
            JCRNodeWrapper fileChunkNode;
            for (long chunkFrom : chunkStarts) {
                fileChunkNode = folderNode.getNode(fileName + "." + chunkFrom + ".chunk");
                try (InputStream is = fileChunkNode.getFileContent().downloadFile()) {
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
                fileChunkNode.remove();
            }
        }
        JCRNodeWrapper node = uploadFile(folderNode, assembledFile, fileName, fileType);
        FileUtils.deleteQuietly(assembledFile);
        return node;
    }
}
