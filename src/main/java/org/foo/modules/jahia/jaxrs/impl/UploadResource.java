package org.foo.modules.jahia.jaxrs.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.foo.modules.jahia.jaxrs.api.FileInfo;
import org.foo.modules.jahia.jaxrs.api.UploadService;
import org.jahia.osgi.BundleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

        List<UploadService> uploadServices = BundleUtils.getOsgiService(UploadServiceRegistrator.class, null).getUploadServices();
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), UploadService.ROOT_FOLDER);
            Map<Boolean, List<FileItem>> fileItems = new ServletFileUpload(new DiskFileItemFactory(10000000, tempDir)).parseRequest(httpServletRequest)
                    .stream().collect(Collectors.groupingBy(FileItem::isFormField));
            Map<String, String> formData = new HashMap<>();
            fileItems.get(true).forEach(item -> formData.put(item.getFieldName(), item.getString()));
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
                        tempDir = new File(System.getProperty("java.io.tmpdir"), UploadService.ROOT_FOLDER);
                        String getTempFolderPropertyInFormData = uploadService.getTempFolderPropertyInFormData();
                        if (getTempFolderPropertyInFormData != null && formData.containsKey(getTempFolderPropertyInFormData)) {
                            tempDir = Files.createDirectories(Paths.get(tempDir.getAbsolutePath(), formData.get(getTempFolderPropertyInFormData).split("/"))).toFile();
                        }
                        File assembledFile = writeFile(tempDir, item, fileInfo, fileFullLength, chunkFrom, chunkTo);
                        if (assembledFile != null && uploadService.uploadFile(fileInfo, assembledFile)) {
                            FileUtils.deleteQuietly(assembledFile.getParentFile());
                        }
                    }
                }
            }
            return Response.status(Response.Status.OK).entity(new ObjectMapper().writeValueAsString(fileInfo)).type(MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            logger.error("", e);
            return Response.serverError().build();
        }
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public long getFile(@QueryParam("path") String path, @Context HttpServletRequest httpServletRequest) {
        File dir = Paths.get(System.getProperty("java.io.tmpdir"), UploadService.ROOT_FOLDER, path).toFile();
        TreeMap<Long, Long> chunkStartsToLengths = getChunkStartsToLengths(dir, StringUtils.substringAfterLast(path, "/"));
        return getCommonLength(chunkStartsToLengths);
    }

    private static File writeFile(File tempDir, FileItem fileItem, FileInfo fileInfo, long fileFullLength, long chunkFrom, long chunkTo) throws Exception {
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
        return assembledFile;
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
