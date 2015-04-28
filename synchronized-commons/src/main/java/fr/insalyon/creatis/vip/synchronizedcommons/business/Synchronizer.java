/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fr.insalyon.creatis.vip.synchronizedcommons.business;

import fr.insalyon.creatis.vip.synchronizedcommons.SyncedDevice;
import fr.insalyon.creatis.vip.synchronizedcommons.Synchronization;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.log4j.Logger;

/**
 *
 * @author Tristan Glatard
 * A Synchronizer is a synchronization agent between an EGI Logical File Catalog and 
 * another directory accessible through SSH or Dropbox, specified by the SyncedDevice attribute.
 * Files in a synchronized LFC directory cannot be modified or deleted. 
 */
public class Synchronizer extends Thread {

    private final SyncedDevice sd;      
    private final int fileLimit;        
                                        
    private final long sleepTimeMillis; 
    private final LFCUtils lfcu;        // an object containing methods to transfer files to the synchronized LFC. 
    
    private static final Logger logger = Logger.getLogger(Synchronizer.class);

    /**
     * A constructor.
     * @param sd              the device that will be synchronized with the LFC
     * @param gridaHost       the GRIDA host configured for the LFC
     * @param gridaPort       the GRIDA port configured for the LFC
     * @param gridaProxy      the proxy to access the LFC
     * @param fileLimit       the maximal number of files to transfer at 
                              each iteration of the synchronization loop.
     * @param sleepTimeMillis the time in milliseconds to sleep between two synchronization loops.
     */
    public Synchronizer(SyncedDevice sd, String gridaHost, int gridaPort, String gridaProxy, int fileLimit, long sleepTimeMillis) {
        this.sd = sd;
        this.fileLimit = fileLimit;
        this.sleepTimeMillis = sleepTimeMillis;
        lfcu = new LFCUtils(gridaHost, gridaPort, gridaProxy);
    }

    /**
     * Creates a directory on the local file system. 
     * @param localDir       the directory to create.
     * @throws SyncException 
     */
    private void createLocalDir(String localDir) throws SyncException {
        File dir = new File(localDir.replaceAll("//", "/")); // just in case... 
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                throw new SyncException("Cannot create local directory " + dir.getAbsolutePath());
            }

        }

    }

    /**
     * Main loop. Lists all the active synchronizations and triggers them.
     */
    @Override
    public void run() {
        while (true) {
            List<Synchronization> synchronizations = null;
            try {
                synchronizations = sd.getSynchronization();
            } catch (SyncException ex) {
                    logger.error("Cannot get user accounts: {0}"+ex.getMessage());
                   
                ex.printStackTrace();
            }
            for (Synchronization s : synchronizations) {
                try {
                    if (s.isValidated() && !s.getAuthFailed()) {
                        doSync(s);
                    }
                } catch (SyncException ex) {
                    logger.error("Problem synchronizing user account {0}: {1} "+ex.getMessage());
                    ex.printStackTrace();
                    if (ex.getMessage().contains(sd.getAuthFailedString())) {
                        logger.info("Marking failed authentication for user {0}"+ s.toString());
                        try {
                            sd.setAuthFailed(s);
                        } catch (SyncException ex1) {
                            logger.error("Cannot mark failed authentication for user {0}"+ s.toString());
                            ex.printStackTrace();
                        }
                    } 
                }

            }

            try {
                Thread.sleep(this.sleepTimeMillis);
            } catch (InterruptedException ex) {
                logger.error(ex);
            }
        }
    }

    /** 
     * Main synchronization method. 
     * @param s synchronization to synchronize. 
     * @throws SyncException 
     */
    private void doSync(Synchronization s) throws SyncException {
        
        sd.setSynchronization(s);
        String syncedLFCDir = s.getSyncedLFCDir();
        int countFiles = 0; //this will count how many files are synced for a user

        if (s.isValidated()) {
            HashMap<String, String> remoteFiles = sd.listFiles("/");
            HashMap<String, String> lfcFiles = lfcu.listLFCDir("/", s);
            if (lfcFiles == null || remoteFiles == null) {
                return;
            }

            //SyncedDevice -> LFC
            for (Map.Entry<String, String> p : remoteFiles.entrySet()) {
                if (countFiles < fileLimit) {
                    String syncedShortPath = PathUtils.cleanse(p.getKey()), remoteRevision = p.getValue();
                    String lfcRev = lfcFiles.get(syncedShortPath);
                    
                    if (lfcRev == null) {
                        //file is in SyncedDevice but not in LFC: copy to lfc
                        
                        logger.info("==> (new file)"+String.format("%s - %s - %s -%s / %s",  s.getEmail(), syncedShortPath, syncedLFCDir, countFiles ,fileLimit));
                        if(!ignorePath(syncedShortPath)){
                            copyToLFC(syncedShortPath, s, remoteRevision);
                            countFiles++;
                        }
                        else
                            logger.info("Ignoring file"+ syncedShortPath);
                    } else {
                        if (!remoteRevision.equals(lfcRev)) {
                            //(A)
                            //revisions disagree: SyncedDevice must be right because LFC doesn't generate revisions (files in a synchronied LFC directory cannot be modified).
                            logger.info(String.format("==x> (SyncedDevice:%s;LFC:%s) (%s) %s %s file count at this iteration is %s/%s", remoteRevision, lfcRev, s.getEmail(), syncedShortPath, syncedLFCDir, countFiles, fileLimit));
                            lfcu.deleteFromLFC("/" + syncedShortPath, s);
                            if(!ignorePath(syncedShortPath)){
                                copyToLFC(syncedShortPath, s, remoteRevision);
                                lfcFiles.remove(syncedShortPath);
                                lfcFiles.put(syncedShortPath, remoteRevision);
                                countFiles++;
                            }
                            else
                                logger.info(String.format("Ignoring file %s", syncedShortPath));
                            
                        } else {
                            //revisions are identical: do nothing
                            //logger.info("== "+boxPath+" is in sync in LFC");
                        }
                    }
                } else {
                    logger.info(String.format("Reached %s files for user %s at this iteration: skipping", countFiles, s.getEmail()));
                }
            }

            //LFC->SyncedDevice
            for (Map.Entry<String, String> q : lfcFiles.entrySet()) {
                
                if (countFiles < fileLimit && !ignorePath(q.getKey())) {

                    String lfcPath = q.getKey(), lfcRev = q.getValue();
                    String remoteRevision = remoteFiles.get(lfcPath);

                    if (remoteRevision == null) {
                        //file is in LFC but not in SyncedDevice
                        if (lfcRev.equals("")) {
                            //if LFC file has no revision: copy to SyncedDevice
                            logger.info(String.format( "<== (new file) / (%s) %s (%s/%s)",s.getEmail(), lfcPath, countFiles, fileLimit));
                            createLocalDir(PathUtils.getDirFromPath(PathUtils.getLocalPathFromLFCLong(PathUtils.getLFCLongFromLFCShort(lfcPath, s), sd)));
                            lfcu.getLFCFile(lfcPath, PathUtils.getDirFromPath(PathUtils.getLocalPathFromLFCLong(PathUtils.getLFCLongFromLFCShort(lfcPath, s), sd)), s);//  lfcPath, sd),ua);
                            sd.putFile(PathUtils.getLocalPathFromLFCLong(PathUtils.getLFCLongFromLFCShort(lfcPath, s), sd), PathUtils.getSyncShortFromLFCShort(lfcPath));//"/"+PathUtils.removeSyncedDir(PathUtils.getL(lfcPath, sd),ua));
                            lfcu.setRevision(PathUtils.getLFCLongFromLFCShort(lfcPath, s), sd.getRevision(PathUtils.getSyncShortFromLFCShort(lfcPath)));       
                            countFiles++;
                        } else {  
                            //file has a revision in LFC: it used to be in SyncedDevice but was removed: remove from LFC.
                            logger.info(String.format("==x (%s) %s/%s (%s/%s)", s.getEmail(), syncedLFCDir, lfcPath, countFiles, fileLimit));
                            lfcu.deleteFromLFC("/" + lfcPath, s);
                            countFiles++;
                        }

                    } else {
                        if (lfcRev.equals("")) { //rev "" means the file is there but has no rev. It was never synced with the SyncedDevice.
                            //file is in LFC with no rev and it is also in SyncedDevice. Something wrong must have happened. Assumes syncedDevice is right.
                            logger.info(String.format("==x> (no revision in LFC) (%s) %s %s (%s/%s)", s.getEmail(), lfcPath, syncedLFCDir, countFiles, fileLimit));
                            lfcu.deleteFromLFC("/" + lfcPath, s);
                            copyToLFC(PathUtils.getSyncShortFromLFCShort(lfcPath), s, remoteRevision);
                            countFiles++;
                        } else {
                            if (!lfcRev.equals(remoteRevision)) {
                                //revisions disagree: do nothing, it should have been handled in (A)
                                logger.info(String.format("oops (SyncedDevice:%s;LFC:%s) (%s) %s: this is not supposed to happen at this point...", remoteRevision, lfcRev, s.getEmail(), lfcPath));
                            } else {
                                //revisions are the same: do nothing
                                //logger.info("== "+lfcPath+" is in sync in Dropbox");
                            }
                        }
                    }
                } else {
                    if(ignorePath(q.getKey()))
                        logger.info( String.format("Ignoring file %s", q.getKey()));
                        else
                    logger.info(String.format("Reached %s files for user %s at this iteration: skipping", countFiles, s.getEmail()));
                }
            }
        }
    }

    /**
     * Copies a file from the SyncedDevice to the LFC.
     * @param syncedShortPath the short path of the file in the SyncedDevice
     * @param s the synchronization
     * @param revision the revision of the file in the SyncedDevice
     * @throws SyncException 
     */
    private void copyToLFC(String syncedShortPath, Synchronization s, String revision) throws SyncException {
        createLocalDir(PathUtils.getDirFromPath(PathUtils.getLocalPathFromSyncShort(syncedShortPath, sd, s)));
        sd.getFile(syncedShortPath, PathUtils.getDirFromPath(PathUtils.getLocalPathFromSyncShort(syncedShortPath, sd, s)));
        lfcu.copyToLfc(PathUtils.getLocalPathFromSyncShort(syncedShortPath, sd, s), PathUtils.getDirFromPath(PathUtils.getLFCLongFromSyncShort(syncedShortPath, s)));
        lfcu.setRevision(PathUtils.getLFCLongFromSyncShort(syncedShortPath, s), revision);
    }
    
    /**
     * Returns true if the file path has to be ignored.
     * @param path file path to test
     * @return 
     */
    boolean ignorePath(String path){
        File f = new File(path);
        if(f.getName().startsWith("."))
            return true;
        return false;
    }
}
