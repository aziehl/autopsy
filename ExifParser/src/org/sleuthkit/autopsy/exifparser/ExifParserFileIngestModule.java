/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2014 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sleuthkit.autopsy.exifparser;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.lang.GeoLocation;
import com.drew.lang.Rational;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.logging.Level;
import org.sleuthkit.autopsy.coreutils.ImageUtils;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.ingest.IngestModuleAdapter;
import org.sleuthkit.autopsy.ingest.FileIngestModule;
import org.sleuthkit.autopsy.ingest.IngestServices;
import org.sleuthkit.autopsy.ingest.ModuleDataEvent;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.BlackboardArtifact;
import org.sleuthkit.datamodel.BlackboardAttribute;
import org.sleuthkit.datamodel.BlackboardAttribute.ATTRIBUTE_TYPE;
import org.sleuthkit.datamodel.ReadContentInputStream;
import org.sleuthkit.datamodel.TskCoreException;
import org.sleuthkit.datamodel.TskData;
import org.sleuthkit.datamodel.TskData.TSK_DB_FILES_TYPE_ENUM;

/**
 * Ingest module to parse image Exif metadata. Currently only supports JPEG
 * files. Ingests an image file and, if available, adds it's date, latitude,
 * longitude, altitude, device model, and device make to a blackboard artifact.
 */
public final class ExifParserFileIngestModule extends IngestModuleAdapter implements FileIngestModule {

    private IngestServices services;
    private static final Logger logger = Logger.getLogger(ExifParserFileIngestModule.class.getName());
    private int filesProcessed = 0;
    private boolean filesToFire = false;

    ExifParserFileIngestModule() {
    }
        
    @Override
    public void startUp(org.sleuthkit.autopsy.ingest.IngestJobContext context) throws Exception {
        super.startUp(context);
        services = IngestServices.getDefault();
        logger.log(Level.INFO, "init() {0}", this.toString());
        filesProcessed = 0;
        filesToFire = false;
    }
    
    @Override
    public ResultCode process(AbstractFile content) {

        //skip unalloc
        if (content.getType().equals(TSK_DB_FILES_TYPE_ENUM.UNALLOC_BLOCKS)) {
            return ResultCode.OK;
        }

        // skip known
        if (content.getKnown().equals(TskData.FileKnown.KNOWN)) {
            return ResultCode.OK;
        }

        // update the tree every 1000 files if we have EXIF data that is not being being displayed 
        filesProcessed++;
        if ((filesToFire) && (filesProcessed % 1000 == 0)) {
            services.fireModuleDataEvent(new ModuleDataEvent(ExifParserModuleFactory.getModuleName(), BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF));
            filesToFire = false;
        }
        
        //skip unsupported
        if (!parsableFormat(content)) {
            return ResultCode.OK;
        }

        return processFile(content);
    }

    ResultCode processFile(AbstractFile f) {
        InputStream in = null;
        BufferedInputStream bin = null;

        try {
            in = new ReadContentInputStream(f);
            bin = new BufferedInputStream(in);

            Collection<BlackboardAttribute> attributes = new ArrayList<>();
            Metadata metadata = ImageMetadataReader.readMetadata(bin, true);

            // Date
            ExifSubIFDDirectory exifDir = metadata.getDirectory(ExifSubIFDDirectory.class);
            if (exifDir != null) {
                Date date = exifDir.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL);
                if (date != null) {
                    attributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DATETIME_CREATED.getTypeID(), ExifParserModuleFactory.getModuleName(), date.getTime() / 1000));
                }
            }

            // GPS Stuff
            GpsDirectory gpsDir = metadata.getDirectory(GpsDirectory.class);
            if (gpsDir != null) {
                GeoLocation loc = gpsDir.getGeoLocation();
                if (loc != null) {
                    double latitude = loc.getLatitude();
                    double longitude = loc.getLongitude();
                    attributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_GEO_LATITUDE.getTypeID(), ExifParserModuleFactory.getModuleName(), latitude));
                    attributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE.getTypeID(), ExifParserModuleFactory.getModuleName(), longitude));
                }
                
                Rational altitude = gpsDir.getRational(GpsDirectory.TAG_GPS_ALTITUDE);
                if (altitude != null) {
                    attributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_GEO_ALTITUDE.getTypeID(), ExifParserModuleFactory.getModuleName(), altitude.doubleValue()));
                }
            }

            // Device info
            ExifIFD0Directory devDir = metadata.getDirectory(ExifIFD0Directory.class);
            if (devDir != null) {
                String model = devDir.getString(ExifIFD0Directory.TAG_MODEL);
                if (model != null && !model.isEmpty()) {
                    attributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DEVICE_MODEL.getTypeID(), ExifParserModuleFactory.getModuleName(), model));
                }
                
                String make = devDir.getString(ExifIFD0Directory.TAG_MAKE);
                if (make != null && !make.isEmpty()) {
                    attributes.add(new BlackboardAttribute(ATTRIBUTE_TYPE.TSK_DEVICE_MAKE.getTypeID(), ExifParserModuleFactory.getModuleName(), make));
                }
            }

            // Add the attributes, if there are any, to a new artifact
            if (!attributes.isEmpty()) {
                BlackboardArtifact bba = f.newArtifact(BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF);
                bba.addAttributes(attributes);
                filesToFire = true;
            }
            
            return ResultCode.OK;
        } 
        catch (TskCoreException ex) {
            logger.log(Level.WARNING, "Failed to create blackboard artifact for exif metadata ({0}).", ex.getLocalizedMessage());
            return ResultCode.ERROR;
        } 
        catch (ImageProcessingException ex) {
            logger.log(Level.WARNING, "Failed to process the image file: {0}/{1}({2})", new Object[]{f.getParentPath(), f.getName(), ex.getLocalizedMessage()});
            return ResultCode.ERROR;
        } 
        catch (IOException ex) {
            logger.log(Level.WARNING, "IOException when parsing image file: " + f.getParentPath() + "/" + f.getName(), ex);
            return ResultCode.ERROR;
        } 
        finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (bin != null) {
                    bin.close();
                }
            } catch (IOException ex) {
                logger.log(Level.WARNING, "Failed to close InputStream.", ex);
                return ResultCode.ERROR;
            }
        }
    }

    /**
     * Checks if should try to attempt to extract exif. Currently checks if JPEG
     * image (by signature)
     *
     * @param f file to be checked
     *
     * @return true if to be processed
     */
    private boolean parsableFormat(AbstractFile f) {
        return ImageUtils.isJpegFileHeader(f);
    }

    @Override
    public void shutDown(boolean ingestJobCancelled) {
        logger.log(Level.INFO, "completed exif parsing {0}", this.toString());
        if (filesToFire) {
            //send the final new data event
            services.fireModuleDataEvent(new ModuleDataEvent(ExifParserModuleFactory.getModuleName(), BlackboardArtifact.ARTIFACT_TYPE.TSK_METADATA_EXIF));
        }
    }
}