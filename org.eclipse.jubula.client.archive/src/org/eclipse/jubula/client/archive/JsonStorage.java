/*******************************************************************************
 * Copyright (c) 2016 BREDEX GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     BREDEX GmbH - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.jubula.client.archive;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.persistence.PersistenceException;

import org.apache.commons.io.output.FileWriterWithEncoding;
import org.apache.commons.lang.Validate;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jubula.client.archive.dto.ExportInfoDTO;
import org.eclipse.jubula.client.archive.dto.ProjectDTO;
import org.eclipse.jubula.client.archive.dto.TestresultSummaryDTO;
import org.eclipse.jubula.client.archive.i18n.Messages;
import org.eclipse.jubula.client.core.Activator;
import org.eclipse.jubula.client.core.businessprocess.IParamNameMapper;
import org.eclipse.jubula.client.core.businessprocess.IWritableComponentNameCache;
import org.eclipse.jubula.client.core.businessprocess.ParamNameBPDecorator;
import org.eclipse.jubula.client.core.businessprocess.ProjectNameBP;
import org.eclipse.jubula.client.core.model.IProjectPO;
import org.eclipse.jubula.client.core.persistence.PMException;
import org.eclipse.jubula.client.core.persistence.PMReadException;
import org.eclipse.jubula.client.core.persistence.PMSaveException;
import org.eclipse.jubula.client.core.persistence.ProjectPM;
import org.eclipse.jubula.client.core.progress.IProgressConsole;
import org.eclipse.jubula.tools.internal.constants.StringConstants;
import org.eclipse.jubula.tools.internal.exception.InvalidDataException;
import org.eclipse.jubula.tools.internal.exception.JBVersionException;
import org.eclipse.jubula.tools.internal.exception.ProjectDeletedException;
import org.eclipse.jubula.tools.internal.messagehandling.MessageIDs;
import org.eclipse.osgi.util.NLS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** @author BREDEX GmbH */
public class JsonStorage {
    
    /** Encoding definition */
    public static final String RECOMMENDED_CHAR_ENCODING = "UTF-8"; //$NON-NLS-1$
    
    /** Encoding definition */
    public static final String UTF_16_CHAR_ENCODING = "UTF-16"; //$NON-NLS-1$
    
    /** Supported encodings */
    public static final String[] SUPPORTED_CHAR_ENCODINGS = 
            new String[]{RECOMMENDED_CHAR_ENCODING, UTF_16_CHAR_ENCODING};
    
    /** Date formatter */
    public static final SimpleDateFormat FORMATTER =
            new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.S"); //$NON-NLS-1$
    
    /** Extension of project file */
    public static final String PJT = "pjt"; //$NON-NLS-1$
    
    /** Extension of test result summaries file */
    public static final String RST = "rst"; //$NON-NLS-1$

    /** Extension of info file */
    public static final String NFO = "nfo"; //$NON-NLS-1$
    
    /** */
    private static final String IMPORT_FOLDER_NAME = "JubImportTemp"; //$NON-NLS-1$
    
    /** Standard logging */
    private static Logger log = LoggerFactory.getLogger(JsonStorage.class);
    
    
    /**
     * @param proj original project object
     * @param fileName Jubula file name
     * @param includeTestResultSummaries true if project contain test result summaries
     * @param monitor loader monitor
     * @param console 
     * @return ProjectDTO 
     * @throws PMException
     * @throws ProjectDeletedException 
     * @throws InterruptedException 
     */
    public static ProjectDTO save(IProjectPO proj, String fileName,
            boolean includeTestResultSummaries, IProgressMonitor monitor,
            IProgressConsole console)
                    throws PMException, ProjectDeletedException {
        
        monitor.beginTask(Messages.XmlStorageSavingProject, 
                getWorkToSave(proj, includeTestResultSummaries));
        monitor.subTask(Messages.ImportJsonStoragePreparing);
        Validate.notNull(proj);
        try {
            if (fileName == null) {
                JsonExporter exporter = new JsonExporter(proj, monitor);
                return exporter.getProjectDTO();
            }
            writeToFile(proj, monitor, fileName, includeTestResultSummaries);
        } catch (FileNotFoundException e) {
            log.info(Messages.File + StringConstants.SPACE 
                    + Messages.NotFound);
            console.writeStatus(new Status(IStatus.WARNING,
                    Activator.PLUGIN_ID, Messages.NotFound));
            throw new PMSaveException(Messages.File + StringConstants.SPACE 
                    + fileName + Messages.NotFound + StringConstants.COLON 
                    + StringConstants.SPACE 
                    + e.toString(), MessageIDs.E_FILE_IO);
        } catch (IOException e) {
            // If the operation has been canceled, then this is just
            // a result of canceling the IO.
            if (!monitor.isCanceled()) {
                log.warn(Messages.GeneralIoExeption);
                console.writeStatus(new Status(IStatus.WARNING,
                        Activator.PLUGIN_ID, Messages.GeneralIoExeption));
                throw new PMSaveException(Messages.GeneralIoExeption 
                        + e.toString(), MessageIDs.E_FILE_IO);
            }
        } catch (PersistenceException e) {
            log.warn(Messages.CouldNotInitializeProxy 
                    + StringConstants.DOT);
            console.writeStatus(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                    Messages.CouldNotInitializeProxy));
            throw new PMSaveException(e.getMessage(),
                MessageIDs.E_DATABASE_GENERAL);
        } catch (OperationCanceledException e) {
            // Operation was cancelled.
            log.info(Messages.ExportOperationCanceled);
            console.writeStatus(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                    Messages.ExportOperationCanceled));
        }
        return null;
    }
    
    /**
     * @param proj original project object
     * @param monitor loader monitor
     * @param fileName Jubula file name
     * @param includeTestResultSummaries true if project contain test result summaries
     * @throws ProjectDeletedException
     * @throws PMException
     * @throws IOException
     * @throws InterruptedException 
     */
    private static void writeToFile(IProjectPO proj, IProgressMonitor monitor,
            String fileName, boolean includeTestResultSummaries)
                    throws ProjectDeletedException, PMException, IOException {
        
        String dir = Files.createTempDirectory(IMPORT_FOLDER_NAME).toString()
                + File.separatorChar;
        String infoFileName = dir + NFO;
        String projectFileName = dir + PJT;
        String testResultFileName = dir + RST;

        FileWriterWithEncoding infoFileWriter = new FileWriterWithEncoding(
                infoFileName, RECOMMENDED_CHAR_ENCODING);
        FileWriterWithEncoding projectFileWriter = new FileWriterWithEncoding(
                projectFileName, RECOMMENDED_CHAR_ENCODING);
        FileWriterWithEncoding resultFileWriter = new FileWriterWithEncoding(
                testResultFileName, RECOMMENDED_CHAR_ENCODING);
        
        ArrayList<String> fileList = new ArrayList<String>();
        ObjectMapper mapper = new ObjectMapper(); 
        mapper.setSerializationInclusion(Include.NON_EMPTY);

        ExportInfoDTO exportDTO = new ExportInfoDTO();
        exportDTO.setDate(FORMATTER.format(new Date()));
        exportDTO.setEncoding(RECOMMENDED_CHAR_ENCODING);
        exportDTO.setVersion(JsonVersion.CURRENTLY_JSON_VERSION);
        
        try {
            mapper.writeValue(infoFileWriter, exportDTO);
            fileList.add(infoFileName);
            JsonExporter exporter = new JsonExporter(proj, monitor);
            ProjectDTO projectDTO = exporter.getProjectDTO();

            File testResultFile = new File(testResultFileName);
            mapper.writeValue(projectFileWriter, projectDTO);
            fileList.add(projectFileName);
            if (includeTestResultSummaries) {
                exporter.writeTestResultSummariesToFile(resultFileWriter);
                fileList.add(testResultFileName);
            }
            monitor.subTask(Messages.ImportJsonStorageCompress);
            fileWriterClose(infoFileWriter);
            fileWriterClose(projectFileWriter);
            fileWriterClose(resultFileWriter);
            
            zipIt(fileName, fileList);
        } catch (Exception e) {
            fileList.add(fileName);
            throw e;
        } finally {
            fileList.add(dir);
            deleteFiles(fileList);
        }
    }
    
    /**
     * @param fileWriter what we need to close
     */
    private static void fileWriterClose(
            FileWriterWithEncoding fileWriter) {
        if (fileWriter != null) {
            try {
                fileWriter.close();
            } catch (IOException e) {
                log.warn(Messages.CantCloseOOS + fileWriter.toString());
            }
        }
    }
    
    /** 
     * @param files what we need to delete
     * @throws IOException
     */
    private static void deleteFiles(List<String> files) {
        for (String fileSrt : files) {
            try {
                Files.deleteIfExists(new File(fileSrt).toPath());
            } catch (IOException e) {
                log.warn(Messages.CantDeleteFile + fileSrt);
            }
        }
    }

    /**
     * @param project The project for which the work is predicted.
     * @param includeTestResultSummaries true if test result summary needed.
     * @return The predicted amount of work required to save a project.
     */
    public static int getWorkToSave(IProjectPO project,
            boolean includeTestResultSummaries) {
        return JsonExporter.getPredictedWork(project,
                includeTestResultSummaries);
    }

    /**
     * @param projectsToSave The projects for which the work is predicted.
     * @return The predicted amount of work required to save the given projects.
     */
    public static int getWorkToSave(List<IProjectPO> projectsToSave) {
        int totalWork = 0;
        
        for (IProjectPO project : projectsToSave) {
            totalWork += getWorkToSave(project, false);
        }

        return totalWork;
    }
    
    /**
     * @param url of import file
     * @param paramNameMapper 
     * @param compNameCache 
     * @param assignNewGuid 
     * @param testResultNeeded 
     * @param monitor 
     * @param io console
     * @return IProjectPO new project object 
     * @throws JBVersionException 
     * @throws PMReadException 
     * @throws InterruptedException 
     * @throws PMSaveException 
     */
    public IProjectPO readProject(URL url, ParamNameBPDecorator paramNameMapper,
            final IWritableComponentNameCache compNameCache,
            boolean assignNewGuid, boolean testResultNeeded,
            IProgressMonitor monitor, IProgressConsole io)
                    throws JBVersionException, PMReadException,
                    InterruptedException {

        SubMonitor subMonitor = SubMonitor.convert(monitor, Messages
                .ImportFileBPReading, testResultNeeded ? 2 : 1);
        
        IProjectPO projectPO = null;
        String folderSrc = null;
        try {
            monitor.subTask(Messages.ImportJsonStoragePreparing);
            
            folderSrc = Files.createTempDirectory(IMPORT_FOLDER_NAME)
                    .toString();
            String fileName = url.getPath().substring(
                    url.getPath().lastIndexOf(File.separatorChar) + 1,
                    url.getPath().lastIndexOf(StringConstants.DOT));
            fileName = URLDecoder.decode(fileName, RECOMMENDED_CHAR_ENCODING);
            String path = URLDecoder.decode(url.getPath(),
                    RECOMMENDED_CHAR_ENCODING);
            unZipIt(path, folderSrc);

            File infoFile = new File(folderSrc + File.separatorChar + NFO);
            File projectFile = new File(folderSrc + File.separatorChar + PJT);
            File resultFile = new File(folderSrc + File.separatorChar + RST);
            ObjectMapper mapper = new ObjectMapper();
            
            ExportInfoDTO exportDTO = mapper.readValue(infoFile,
                    ExportInfoDTO.class);
            
            checkMinimumRequiredJSONVersion(exportDTO);
            
            ProjectDTO projectDTO = mapper.readValue(projectFile,
                    ProjectDTO.class);
            
            if (projectExists(projectDTO)) {
                existProjectHandling(io, projectDTO);
                return null;
            }
            
            projectPO = load(projectDTO, subMonitor.newChild(1), io,
                    assignNewGuid, paramNameMapper, compNameCache, false);

            if (testResultNeeded) {
                JsonImporter importer = new JsonImporter(monitor, io, false);
                ArrayList<TestresultSummaryDTO> resultDTOs =
                        mapper.readValue(resultFile, new TypeReference
                        <ArrayList<TestresultSummaryDTO>>() { });
                
                importer.initTestResultSummaries(subMonitor.newChild(1),
                        resultDTOs, projectPO);
            }
        } catch (IOException e) {
            // If the operation has been canceled, then this is just
            // a result of canceling the IO.
            if (!monitor.isCanceled()) {
                log.info(Messages.GeneralIoExeption);
                throw new PMReadException(Messages.InvalidImportFile,
                        MessageIDs.E_IO_EXCEPTION);
            }
        } finally {
            if (folderSrc != null) {
                deleteFiles(Arrays.asList(folderSrc));
            }
        }

        return projectPO;
    }
    
    /**
     * @param dto project dto
     * @param monitor 
     * @param io console
     * @param assignNewGuid need it a new guid or not 
     * @param paramNameMapper 
     * @param compNameCache 
     * @param skipTrackingInformation  
     * @return IProjectPO 
     * @throws JBVersionException
     * @throws InterruptedException
     * @throws PMReadException 
     */
    public static IProjectPO load(ProjectDTO dto, IProgressMonitor monitor,
            IProgressConsole io, boolean assignNewGuid, 
            IParamNameMapper paramNameMapper,
            IWritableComponentNameCache compNameCache,
            boolean skipTrackingInformation)
                    throws JBVersionException, InterruptedException,
                    PMReadException {

        IProjectPO projectPO = null;
        try {
            JsonImporter importer = new JsonImporter(monitor, io,
                    skipTrackingInformation);
            projectPO = importer.createProject(dto, assignNewGuid,
                    paramNameMapper, compNameCache);
        } catch (InvalidDataException e) {
            throw new PMReadException(Messages.InvalidImportFile,
                e.getErrorId());
        } 
        
        return projectPO;
    }

    /**
     * @param io console
     * @param projectDTO 
     */
    private void existProjectHandling(IProgressConsole io,
            ProjectDTO projectDTO) {
        
        String msg = NLS.bind(Messages.ErrorMessageIMPORT_PROJECT_XML_FAILED,
                new String [] {ProjectNameBP.getInstance().getName(
                        projectDTO.getGuid(), false)})
            + StringConstants.NEWLINE
            + NLS.bind(Messages.ErrorMessageIMPORT_PROJECT_XML_FAILED_EXISTING,
                new String [] {projectDTO.getName(),
                        projectDTO.getProjectVersion().toString()});
        
        io.writeStatus(new Status(IStatus.INFO, Activator.PLUGIN_ID, msg));
    }

    /**
     * @param dto the project dto
     * @throws JBVersionException
     *             in case of version conflict between given dto and minimum dto
     *             version number; if these versions do not fit the current
     *             available converter are not able to convert the given project
     *             dto properly.
     */
    private void checkMinimumRequiredJSONVersion(ExportInfoDTO dto)
        throws JBVersionException {
        if (dto.getVersion() == null
                || !JsonVersion.isCompatible(dto.getVersion())) {
            List<String> errorMsgs = new ArrayList<String>();
            errorMsgs.add(Messages.JubImporterProjectJUBTooOld);
            throw new JBVersionException(
                    Messages.JubImporterProjectJUBTooOld,
                    MessageIDs.E_LOAD_PROJECT_XML_VERSION_ERROR,
                    errorMsgs);
        }
    }

    /**
     * Zip it
     * @param zipFile output ZIP file location
     * @param fileList it contains files what we would like to compress
     * @throws IOException 
     */
    private static void zipIt(String zipFile, ArrayList<String> fileList)
            throws IOException {

        byte[] buffer = new byte[1024];
        FileOutputStream fos = new FileOutputStream(zipFile);
        ZipOutputStream zos = new ZipOutputStream(fos);
            
        for (String file : fileList) {
            String fileName = file.substring(file.lastIndexOf(
                    File.separator) + 1);
            ZipEntry ze = new ZipEntry(fileName);
            zos.putNextEntry(ze);
            FileInputStream in = new FileInputStream(file);
           
            int len;
            while ((len = in.read(buffer)) > 0) {
                zos.write(buffer, 0, len);
            }
            in.close();
        }
        zos.closeEntry();
        zos.close();
    }
    
    /**
     * Unzip it
     * @param zipFile input zip file
     * @param folderSrc where will be the unziped files  
     * @throws IOException 
     */
    private static void unZipIt(String zipFile, String folderSrc)
            throws IOException {
        
        byte[] buffer = new byte[1024];
        File folder = new File(folderSrc);
        if (!folder.exists()) {
            folder.mkdir();
        }
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        ZipEntry ze = zis.getNextEntry();
            
        while (ze != null) {
            String fileName = ze.getName();
            File newFile = new File(folderSrc + File.separator + fileName);
            new File(newFile.getParent()).mkdirs();
            FileOutputStream fos = new FileOutputStream(newFile);
            int len;
            
            while ((len = zis.read(buffer)) > 0) {
                fos.write(buffer, 0, len);
            }
            fos.close();   
            ze = zis.getNextEntry();
        }
        zis.closeEntry();
        zis.close();
    }
    
    /**
     * @param dto ProjectDTO what we wanted to import. 
     * @return <code>true</code> if another project with the same GUID and
     *         version number as the currently imported project already 
     *         exists in the database. Otherwise <code>false</code>.
     */
    private boolean projectExists(ProjectDTO dto) {
        
        return ProjectPM.doesProjectVersionExist(dto.getGuid(),
                dto.getMajorProjectVersion(), dto.getMinorProjectVersion(),
                dto.getMicroProjectVersion(), dto.getProjectVersionQualifier());
    }
}