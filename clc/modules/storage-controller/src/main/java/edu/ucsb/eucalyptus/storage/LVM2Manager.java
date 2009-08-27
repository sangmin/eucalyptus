/*
 * Software License Agreement (BSD License)
 *
 * Copyright (c) 2008, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use of this software in source and binary forms, with or
 * without modification, are permitted provided that the following conditions
 * are met:
 *
 * * Redistributions of source code must retain the above
 *   copyright notice, this list of conditions and the
 *   following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the
 *   following disclaimer in the documentation and/or other
 *   materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * Author: Sunil Soman sunils@cs.ucsb.edu
 */

package edu.ucsb.eucalyptus.storage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.eucalyptus.auth.Hashes;
import com.eucalyptus.util.EntityWrapper;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.StorageProperties;
import com.eucalyptus.util.WalrusProperties;

import edu.ucsb.eucalyptus.cloud.entities.LVMMetaInfo;
import edu.ucsb.eucalyptus.cloud.entities.LVMVolumeInfo;
import edu.ucsb.eucalyptus.cloud.ws.StreamConsumer;
import edu.ucsb.eucalyptus.cloud.ws.SystemUtil;

public class LVM2Manager implements LogicalStorageManager {


	public static final String lvmRootDirectory = "/dev";
	public static final String PATH_SEPARATOR = "/";
	public static boolean initialized = false;
	public static String hostName = "localhost";
	public static final int MAX_LOOP_DEVICES = 256;
	public static final int MAX_MINOR_NUMBER = 16;
	private  static final String blockSize = "1M";
	public static final String EUCA_ROOT_WRAPPER = "/usr/lib/eucalyptus/euca_rootwrap";
	public static final String EUCA_VAR_RUN_PATH = "/var/run/eucalyptus";
	private static Logger LOG = Logger.getLogger(LVM2Manager.class);
	public static String eucaHome = System.getProperty("euca.home");
	private static final String IFACE_CONFIG_STRING = "VNET_INTERFACE";
	private static final boolean ifaceDiscovery = false;
	private static final long LVM_HEADER_LENGTH = 4 * StorageProperties.MB;
	public StorageExportManager exportManager;

	public void checkPreconditions() throws EucalyptusCloudException {
		//check if binaries exist, commands can be executed, etc.
		String eucaHomeDir = System.getProperty("euca.home");
		if(eucaHomeDir == null) {
			throw new EucalyptusCloudException("euca.home not set");
		}
		eucaHome = eucaHomeDir;
		if(!new File(eucaHome + EUCA_ROOT_WRAPPER).exists()) {
			throw new EucalyptusCloudException("root wrapper (euca_rootwrap) does not exist in " + eucaHome + EUCA_ROOT_WRAPPER);
		}
		File varDir = new File(eucaHome + EUCA_VAR_RUN_PATH);
		if(!varDir.exists()) {
			varDir.mkdirs();
		}
		String returnValue = getLvmVersion();
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Is lvm installed?");
		} else {
			LOG.info(returnValue);
		}
		returnValue = getVblade();
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("vblade not found: Is it installed?");
		} else {
			LOG.info(returnValue);
		}
	}

	private String getLvmVersion() {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "lvm", "version"});
	}

	private String findFreeLoopback() {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "losetup", "-f"}).replaceAll("\n", "");
	}

	private  String getLoopback(String loDevName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "losetup", loDevName});
	}

	private String createPhysicalVolume(String loDevName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "pvcreate", loDevName});
	}

	private String createVolumeGroup(String pvName, String vgName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "vgcreate", vgName, pvName});
	}

	private String extendVolumeGroup(String pvName, String vgName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "vgextend", vgName, pvName});
	}

	private String createLogicalVolume(String vgName, String lvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "lvcreate", "-n", lvName, "-l", "100%FREE", vgName});
	}

	private String createSnapshotLogicalVolume(String lvName, String snapLvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "lvcreate", "-n", snapLvName, "-s", "-l", "100%FREE", lvName});
	}

	private String removeLogicalVolume(String lvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "lvremove", "-f", lvName});
	}

	private String disableLogicalVolume(String lvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "lvchange", "-an", lvName});
	}

	private String removeVolumeGroup(String vgName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "vgremove", vgName});
	}

	private String removePhysicalVolume(String loDevName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "pvremove", loDevName});
	}

	private String removeLoopback(String loDevName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "losetup", "-d", loDevName});
	}

	private String reduceVolumeGroup(String vgName, String pvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "vgreduce", vgName, pvName});
	}

	private String suspendDevice(String deviceName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "dmsetup", "-v", "suspend", deviceName});
	}

	private String resumeDevice(String deviceName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "dmsetup", "-v", "resume", deviceName});
	}

	private String getAoEStatus(String pid) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "cat", "/proc/", pid, "/cmdline"});
	}

	private String enableLogicalVolume(String lvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "lvchange", "-ay", lvName});
	}

	private String getVblade() {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "which", "vblade"});
	}

	private int losetup(String absoluteFileName, String loDevName) {
		try
		{
			Runtime rt = Runtime.getRuntime();
			Process proc = rt.exec(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "losetup", loDevName, absoluteFileName});
			StreamConsumer error = new StreamConsumer(proc.getErrorStream());
			StreamConsumer output = new StreamConsumer(proc.getInputStream());
			error.start();
			output.start();
			int errorCode = proc.waitFor();
			output.join();
			LOG.info("losetup " + loDevName + " " + absoluteFileName);
			LOG.info(output.getReturnValue());
			LOG.info(error.getReturnValue());
			return errorCode;
		} catch (Throwable t) {
			LOG.error(t);
		}
		return -1;
	}

	private String duplicateLogicalVolume(String oldLvName, String newLvName) {
		return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "dd", "if=" + oldLvName, "of=" + newLvName, "bs=" + blockSize});
	}

	private String createFile(String fileName, long size) {
		if(!StorageProperties.zeroFillVolumes)
			return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "dd", "if=/dev/zero", "of=" + fileName, "count=1", "bs=" + blockSize, "seek=" + (size -1)});
		else
			return SystemUtil.run(new String[]{eucaHome + EUCA_ROOT_WRAPPER, "dd", "if=/dev/zero", "of=" + fileName, "count=" + size, "bs=" + blockSize});
	}

	private String createEmptyFile(String fileName, int size) {
		long fileSize = size * 1024;
		return createFile(fileName, fileSize);
	}

	public String createAbsoluteEmptyFile(String fileName, long size) {
		size = size / WalrusProperties.M;
		return createFile(fileName, size);
	}


	public void initialize() {
		if(!initialized) {
			System.loadLibrary("lvm2control");
			exportManager = new AOEManager();
			initialized = true;
		}
	}

	public void configure() {
		try {
			hostName = InetAddress.getLocalHost().getHostName();
			/*StorageProperties.iface = parseConfig();
            LOG.info("Will export volumes on interface: " + StorageProperties.iface);
            if(StorageProperties.iface == null || (StorageProperties.iface.length() == 0)) {
                NetworkInterface inface = NetworkInterface.getByName(StorageProperties.iface);
                if(inface == null) {
                    LOG.error("Network interface " + StorageProperties.iface + " is not valid. BlockStorage may not function.");
                    if(ifaceDiscovery) {
                        List<NetworkInterface> ifaces = null;
                        try {
                            ifaces = Collections.list( NetworkInterface.getNetworkInterfaces() );
                        } catch ( SocketException e1 ) {}
                        for ( NetworkInterface ifc : ifaces )
                            try {
                                if ( !ifc.isLoopback() && !ifc.isVirtual() && ifc.isUp() ) {
                                    StorageProperties.iface = ifc.getName();
                                    break;
                                }
                            } catch ( SocketException e1 ) {}
                    }
                } else {
                    if(!inface.isUp()) {
                        LOG.error("Network interface " + StorageProperties.iface + " is not available (up). BlockStorage may not function.");
                    }
                }
            }
			 */
			EntityWrapper<LVMMetaInfo> db = new EntityWrapper<LVMMetaInfo>();
			LVMMetaInfo metaInfo = new LVMMetaInfo(hostName);
			List<LVMMetaInfo> metaInfoList = db.query(metaInfo);
			if(metaInfoList.size() <= 0) {
				metaInfo.setMajorNumber(0);
				metaInfo.setMinorNumber(0);
				db.add(metaInfo);
			}
			db.commit();
		} catch(Exception ex) {
			ex.printStackTrace();
		}
	}


	/*private String parseConfig() {
		String configFileName = eucaHome + CONFIG_FILE_PATH;
		String ifaceName = null;
		try {
			FileInputStream fileInputStream = new FileInputStream(new File(configFileName));
			BufferedReader reader = new BufferedReader(new InputStreamReader(fileInputStream));
			String line;
			while((line = reader.readLine()) !=null) {
				if(line.contains(IFACE_CONFIG_STRING) && !line.startsWith("#")) {
					String[] parts = line.split("=");
					if(parts.length > 1) {
						ifaceName = parts[1];
						ifaceName = ifaceName.replaceAll('\"' + "", "");
					}
					break;
				}
			}
		} catch (Exception ex) {
			LOG.error("Could not parse config file " + configFileName);
		}
		return ifaceName;
	}*/

	public void startupChecks() {
		reload();
	}

	public void setStorageInterface(String storageInterface) {
		StorageProperties.iface = storageInterface;
	}

	public void cleanVolume(String volumeId) {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(volumeId);
		List<LVMVolumeInfo> lvmVolumeInfos = db.query(lvmVolumeInfo);
		if(lvmVolumeInfos.size() > 0) {
			LVMVolumeInfo lvmVolInfo = lvmVolumeInfos.get(0);
			//remove aoe export
			String loDevName = lvmVolInfo.getLoDevName();
			int pid = lvmVolInfo.getVbladePid();
			if(pid > 0) {
				String returnValue = aoeStatus(pid);
				if(returnValue.length() > 0) {
					exportManager.unexportVolume(pid);
					int majorNumber = lvmVolInfo.getMajorNumber();
					int minorNumber = lvmVolInfo.getMinorNumber();
					File vbladePidFile = new File(eucaHome + EUCA_VAR_RUN_PATH + "/vblade-" + majorNumber + minorNumber + ".pid");
					if(vbladePidFile.exists()) {
						vbladePidFile.delete();
					}
				}
			}
			String vgName = lvmVolInfo.getVgName();
			String lvName = lvmVolInfo.getLvName();
			String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;

			String returnValue = removeLogicalVolume(absoluteLVName);
			returnValue = removeVolumeGroup(vgName);
			returnValue = removePhysicalVolume(loDevName);
			removeLoopback(loDevName);
			db.delete(lvmVolInfo);
			db.commit();
		}
	}

	public void cleanSnapshot(String snapshotId) {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
		List<LVMVolumeInfo> lvmVolumeInfos = db.query(lvmVolumeInfo);
		if(lvmVolumeInfos.size() > 0) {
			LVMVolumeInfo lvmVolInfo = lvmVolumeInfos.get(0);
			db.delete(lvmVolInfo);
			db.commit();
		}
	}

	public native void registerSignals();

	private synchronized List<Integer> allocateDeviceNumbers() throws EucalyptusCloudException {
		int majorNumber = -1;
		int minorNumber = -1;
		List<Integer> deviceNumbers = new ArrayList<Integer>();
		LVMMetaInfo metaInfo = new LVMMetaInfo(hostName);
		EntityWrapper<LVMMetaInfo> db = new EntityWrapper<LVMMetaInfo>();
		List<LVMMetaInfo> metaInfoList = db.query(metaInfo);
		if(metaInfoList.size() > 0) {
			LVMMetaInfo foundMetaInfo = metaInfoList.get(0);
			majorNumber = foundMetaInfo.getMajorNumber();
			minorNumber = foundMetaInfo.getMinorNumber();
			do {
				if(minorNumber >= MAX_MINOR_NUMBER - 1) {
					++majorNumber;
				}
				minorNumber = (minorNumber + 1) % MAX_MINOR_NUMBER;
				LOG.info("Trying e" + majorNumber + "." + minorNumber);
			} while(new File(StorageProperties.ETHERD_PREFIX + majorNumber + "." + minorNumber).exists());
			foundMetaInfo.setMajorNumber(majorNumber);
			foundMetaInfo.setMinorNumber(minorNumber);
		}
		deviceNumbers.add(majorNumber);
		deviceNumbers.add(minorNumber);
		db.commit();
		return deviceNumbers;
	}

	public int exportVolume(LVMVolumeInfo lvmVolumeInfo, String vgName, String lvName) throws EucalyptusCloudException {
		List<Integer> deviceNumbers = allocateDeviceNumbers();
		int majorNumber = deviceNumbers.get(0);
		int minorNumber = deviceNumbers.get(1);
		String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;
		int pid = exportManager.exportVolume(StorageProperties.iface, absoluteLVName, majorNumber, minorNumber);
		boolean success = false;
		String returnValue = "";
		int timeout = 300;
		if(pid > 0) {
			for(int i=0; i < 5; ++i) {
				returnValue = aoeStatus(pid);
				if(returnValue.length() == 0) {
					success = false;
					try {
						Thread.sleep(timeout);
					} catch(InterruptedException ie) {
						LOG.error(ie);
					}
					timeout += 300;
				} else {
					success = true;
					break;
				}
			}
		}
		if(!success) {
			throw new EucalyptusCloudException("Could not export AoE device " + absoluteLVName + " iface: " + StorageProperties.iface + " pid: " + pid + " returnValue: " + returnValue);
		}

		File vbladePidFile = new File(eucaHome + EUCA_VAR_RUN_PATH + "/vblade-" + majorNumber + minorNumber + ".pid");
		try {
			FileOutputStream fileOutStream = new FileOutputStream(vbladePidFile);
			String pidString = String.valueOf(pid);
			fileOutStream.write(pidString.getBytes());
			fileOutStream.close();
		} catch (Exception ex) {
			LOG.error("Could not write pid file vblade-" + majorNumber + minorNumber + ".pid");
		}
		lvmVolumeInfo.setVbladePid(pid);
		lvmVolumeInfo.setMajorNumber(majorNumber);
		lvmVolumeInfo.setMinorNumber(minorNumber);
		return pid;
	}

	public void dupFile(String oldFileName, String newFileName) {
		try {
			FileChannel out = new FileOutputStream(new File(newFileName)).getChannel();
			FileChannel in = new FileInputStream(new File(oldFileName)).getChannel();
			in.transferTo(0, in.size(), out);
			in.close();
			out.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	public String createDuplicateLoopback(String oldRawFileName, String rawFileName) throws EucalyptusCloudException {
		dupFile(oldRawFileName, rawFileName);
		return createLoopback(rawFileName);
	}

	public String createLoopback(String fileName, int size) throws EucalyptusCloudException {
		createEmptyFile(fileName, size);
		if(!(new File(fileName).exists()))
			throw new EucalyptusCloudException("Unable to create file " + fileName);
		return createLoopback(fileName);
	}

	public String createLoopback(String fileName) throws EucalyptusCloudException {
		int number_of_retries = 0;
		int status = -1;
		String loDevName;
		do {
			loDevName = findFreeLoopback();
			if(loDevName.length() > 0) {
				status = losetup(fileName, loDevName);
			}
			if(number_of_retries++ >= MAX_LOOP_DEVICES)
				break;
		} while(status != 0);

		if(status != 0) {
			throw new EucalyptusCloudException("Could not create loopback device for " + fileName +
			". Please check the max loop value and permissions");
		}
		return loDevName;
	}

	public int createLoopback(String absoluteFileName, String loDevName) {
		return losetup(absoluteFileName, loDevName);
	}

	public String createLoopback(String fileName, long size) throws EucalyptusCloudException {
		createAbsoluteEmptyFile(fileName, size);
		if(!(new File(fileName).exists()))
			throw new EucalyptusCloudException("Unable to create file " + fileName);
		return createLoopback(fileName);
	}

	//creates a logical volume (and a new physical volume and volume group)
	public void createLogicalVolume(String loDevName, String vgName, String lvName) throws EucalyptusCloudException {
		String returnValue = createPhysicalVolume(loDevName);
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Unable to create physical volume for " + loDevName);
		}
		returnValue = createVolumeGroup(loDevName, vgName);
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Unable to create volume group " + vgName + " for " + loDevName);
		}
		returnValue = createLogicalVolume(vgName, lvName);
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Unable to create logical volume " + lvName + " in volume group " + vgName);
		}
	}

	public  void createSnapshotLogicalVolume(String loDevName, String vgName, String lvName, String snapLvName) throws EucalyptusCloudException {
		String returnValue = createPhysicalVolume(loDevName);
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Unable to create physical volume for " + loDevName);
		}
		returnValue = extendVolumeGroup(loDevName, vgName);
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Unable to extend volume group " + vgName + " for " + loDevName);
		}
		returnValue = createSnapshotLogicalVolume(lvName, snapLvName);
		if(returnValue.length() == 0) {
			throw new EucalyptusCloudException("Unable to create snapshot logical volume " + snapLvName + " for volume " + lvName);
		}
	}

	public void createVolume(String volumeId, int size) throws EucalyptusCloudException {
		File volumeDir = new File(StorageProperties.storageRootDirectory);
		volumeDir.mkdirs();

		String vgName = "vg-" + Hashes.getRandom(4);
		String lvName = "lv-" + Hashes.getRandom(4);
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo();

		String rawFileName = StorageProperties.storageRootDirectory + "/" + volumeId;
		//create file and attach to loopback device
		long absoluteSize = size * StorageProperties.GB + LVM_HEADER_LENGTH;
		String loDevName = createLoopback(rawFileName, absoluteSize);
		//create physical volume, volume group and logical volume
		createLogicalVolume(loDevName, vgName, lvName);
		//export logical volume
		try {
			int vbladePid = exportVolume(lvmVolumeInfo, vgName, lvName);
			if(vbladePid < 0) {
				throw new EucalyptusCloudException("Unable to export volume: " + volumeId);
			}
		} catch(EucalyptusCloudException ex) {
			LOG.error(ex);
			String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;
			String returnValue = removeLogicalVolume(absoluteLVName);
			returnValue = removeVolumeGroup(vgName);
			returnValue = removePhysicalVolume(loDevName);
			removeLoopback(loDevName);
			throw ex;
		}

		lvmVolumeInfo.setVolumeId(volumeId);
		lvmVolumeInfo.setLoDevName(loDevName);
		lvmVolumeInfo.setPvName(loDevName);
		lvmVolumeInfo.setVgName(vgName);
		lvmVolumeInfo.setLvName(lvName);
		lvmVolumeInfo.setStatus(StorageProperties.Status.available.toString());
		lvmVolumeInfo.setSize(size);

		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		db.add(lvmVolumeInfo);
		db.commit();
	}

	public int createVolume(String volumeId, String snapshotId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
		LVMVolumeInfo foundSnapshotInfo = db.getUnique(lvmVolumeInfo);
		int size = -1;
		if(foundSnapshotInfo != null) {
			String status = foundSnapshotInfo.getStatus();
			if(status.equals(StorageProperties.Status.available.toString())) {
				String vgName = "vg-" + Hashes.getRandom(4);
				String lvName = "lv-" + Hashes.getRandom(4);
				lvmVolumeInfo = new LVMVolumeInfo();

				String rawFileName = StorageProperties.storageRootDirectory + "/" + volumeId;
				//create file and attach to loopback device
				File snapshotFile = new File(StorageProperties.storageRootDirectory + PATH_SEPARATOR + foundSnapshotInfo.getVolumeId());
				assert(snapshotFile.exists());
				long absoluteSize = snapshotFile.length() + LVM_HEADER_LENGTH;
				size = (int)(snapshotFile.length() / StorageProperties.GB);
				String loDevName = createLoopback(rawFileName, absoluteSize);
				//create physical volume, volume group and logical volume
				createLogicalVolume(loDevName, vgName, lvName);
				//duplicate snapshot volume
				String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;
				duplicateLogicalVolume(foundSnapshotInfo.getLoFileName(), absoluteLVName);
				//export logical volume
				try {
					int vbladePid = exportVolume(lvmVolumeInfo, vgName, lvName);
					if(vbladePid < 0) {
						throw new EucalyptusCloudException("Unable to export volume: " + volumeId);
					}
				} catch(EucalyptusCloudException ex) {
					String returnValue = removeLogicalVolume(absoluteLVName);
					returnValue = removeVolumeGroup(vgName);
					returnValue = removePhysicalVolume(loDevName);
					removeLoopback(loDevName);
					throw ex;
				}
				lvmVolumeInfo.setVolumeId(volumeId);
				lvmVolumeInfo.setLoDevName(loDevName);
				lvmVolumeInfo.setPvName(loDevName);
				lvmVolumeInfo.setVgName(vgName);
				lvmVolumeInfo.setLvName(lvName);
				lvmVolumeInfo.setStatus(StorageProperties.Status.available.toString());
				lvmVolumeInfo.setSize(size);
				db.add(lvmVolumeInfo);
				db.commit();
			}
		} else {
			db.rollback();
			throw new EucalyptusCloudException("Unable to find snapshot: " + snapshotId);
		}
		return size;
	}

	public void addSnapshot(String snapshotId) throws EucalyptusCloudException {
		String snapshotRawFileName = StorageProperties.storageRootDirectory + "/" + snapshotId;
		File snapshotFile = new File(snapshotRawFileName);
		if(snapshotFile.exists()) {
			EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
			LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
			lvmVolumeInfo.setLoFileName(snapshotRawFileName);
			lvmVolumeInfo.setStatus(StorageProperties.Status.available.toString());
			lvmVolumeInfo.setSize((int)(snapshotFile.length() / StorageProperties.GB));
			lvmVolumeInfo.setVbladePid(-1);			
			db.add(lvmVolumeInfo);
			db.commit();			
		} else {
			throw new EucalyptusCloudException("Snapshot backing file does not exist for: " + snapshotId);
		}
	}
	
	public void dupVolume(String volumeId, String dupVolumeId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(volumeId);
		LVMVolumeInfo foundVolumeInfo = db.getUnique(lvmVolumeInfo);
		if(foundVolumeInfo != null) {
			String vgName = "vg-" + Hashes.getRandom(4);
			String lvName = "lv-" + Hashes.getRandom(4);
			lvmVolumeInfo = new LVMVolumeInfo();

			File volumeFile = new File(StorageProperties.storageRootDirectory + PATH_SEPARATOR + foundVolumeInfo.getVolumeId());

			String rawFileName = StorageProperties.storageRootDirectory + "/" + dupVolumeId;
			//create file and attach to loopback device
			int size = (int)(volumeFile.length() / StorageProperties.GB);
			String loDevName = createLoopback(rawFileName, size);
			//create physical volume, volume group and logical volume
			createLogicalVolume(loDevName, vgName, lvName);
			//duplicate snapshot volume
			String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;
			String absoluteVolumeLVName = lvmRootDirectory + PATH_SEPARATOR + foundVolumeInfo.getVgName() +
			PATH_SEPARATOR + foundVolumeInfo.getLvName();
			duplicateLogicalVolume(absoluteVolumeLVName, absoluteLVName);

			lvmVolumeInfo.setVolumeId(dupVolumeId);
			lvmVolumeInfo.setLoDevName(loDevName);
			lvmVolumeInfo.setPvName(loDevName);
			lvmVolumeInfo.setVgName(vgName);
			lvmVolumeInfo.setLvName(lvName);
			lvmVolumeInfo.setStatus(StorageProperties.Status.available.toString());
			lvmVolumeInfo.setSize(size);
			lvmVolumeInfo.setVbladePid(-1);
			db.add(lvmVolumeInfo);

			db.commit();
		} else {
			db.rollback();
			throw new EucalyptusCloudException("Could not dup volume " + volumeId);
		}

	}

	public List<String> getStatus(List<String> volumeSet) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		ArrayList<String> status = new ArrayList<String>();
		for(String volumeSetEntry: volumeSet) {
			LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo();
			lvmVolumeInfo.setVolumeId(volumeSetEntry);
			LVMVolumeInfo foundLvmVolumeInfo = db.getUnique(lvmVolumeInfo);
			if(foundLvmVolumeInfo != null) {
				status.add(foundLvmVolumeInfo.getStatus());
			} else {
				db.rollback();
				throw new EucalyptusCloudException("Unable to find entry: " + volumeSetEntry);
			}
		}
		db.commit();
		return status;
	}

	public void deleteVolume(String volumeId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(volumeId);
		LVMVolumeInfo foundLVMVolumeInfo = db.getUnique(lvmVolumeInfo);

		if(foundLVMVolumeInfo != null) {
			//remove aoe export
			String loDevName = foundLVMVolumeInfo.getLoDevName();
			String vgName = foundLVMVolumeInfo.getVgName();
			String lvName = foundLVMVolumeInfo.getLvName();
			String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;


			Integer pid = foundLVMVolumeInfo.getVbladePid();
			if(pid > 0) {
				String returnValue = aoeStatus(pid);
				if(returnValue.length() > 0) {
					exportManager.unexportVolume(pid);
					int majorNumber = foundLVMVolumeInfo.getMajorNumber();
					int minorNumber = foundLVMVolumeInfo.getMinorNumber();
					File vbladePidFile = new File(eucaHome + EUCA_VAR_RUN_PATH + "/vblade-" + majorNumber + minorNumber + ".pid");
					if(vbladePidFile.exists()) {
						vbladePidFile.delete();
					}
				}
			}
			String returnValue = removeLogicalVolume(absoluteLVName);
			if(returnValue.length() == 0) {
				throw new EucalyptusCloudException("Unable to remove logical volume " + absoluteLVName);
			}
			returnValue = removeVolumeGroup(vgName);
			if(returnValue.length() == 0) {
				throw new EucalyptusCloudException("Unable to remove volume group " + vgName);
			}
			returnValue = removePhysicalVolume(loDevName);
			if(returnValue.length() == 0) {
				throw new EucalyptusCloudException("Unable to remove physical volume " + loDevName);
			}
			returnValue = removeLoopback(loDevName);
			db.delete(foundLVMVolumeInfo);
			db.commit();
		}  else {
			db.rollback();
			throw new EucalyptusCloudException("Unable to find volume: " + volumeId);
		}
	}


	public List<String> createSnapshot(String volumeId, String snapshotId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(volumeId);
		LVMVolumeInfo foundLVMVolumeInfo = db.getUnique(lvmVolumeInfo);
		ArrayList<String> returnValues = new ArrayList<String>();
		if(foundLVMVolumeInfo != null) {
			LVMVolumeInfo snapshotInfo = new LVMVolumeInfo(snapshotId);
			snapshotInfo.setSnapshotOf(volumeId);
			File snapshotDir = new File(StorageProperties.storageRootDirectory);
			snapshotDir.mkdirs();

			String vgName = foundLVMVolumeInfo.getVgName();
			String lvName = "lv-snap-" + Hashes.getRandom(4);
			String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + foundLVMVolumeInfo.getLvName();

			int size = foundLVMVolumeInfo.getSize();
			long snapshotSize = (size * StorageProperties.GB) / 2;
			String rawFileName = StorageProperties.storageRootDirectory + "/" + volumeId + Hashes.getRandom(6);
			//create file and attach to loopback device
			String loDevName = createLoopback(rawFileName, snapshotSize);
			//create physical volume, volume group and logical volume
			createSnapshotLogicalVolume(loDevName, vgName, absoluteLVName, lvName);

			String snapRawFileName = StorageProperties.storageRootDirectory + "/" + snapshotId;
			String absoluteSnapLVName = lvmRootDirectory + PATH_SEPARATOR + vgName + PATH_SEPARATOR + lvName;

			duplicateLogicalVolume(absoluteSnapLVName, snapRawFileName);

			String returnValue = removeLogicalVolume(absoluteSnapLVName);
			if(returnValue.length() == 0) {
				throw new EucalyptusCloudException("Unable to remove logical volume " + absoluteSnapLVName);
			}
			returnValue = reduceVolumeGroup(vgName, loDevName);
			if(returnValue.length() == 0) {
				throw new EucalyptusCloudException("Unable to reduce volume group " + vgName + " logical volume: " + loDevName);
			}
			returnValue = removePhysicalVolume(loDevName);
			if(returnValue.length() == 0) {
				throw new EucalyptusCloudException("Unable to remove physical volume " + loDevName);
			}
			returnValue = removeLoopback(loDevName);
			if(!(new File(rawFileName)).delete()) {
				LOG.error("Unable to remove temporary snapshot file: " + rawFileName);
			}
			snapshotInfo.setLoFileName(snapRawFileName);
			snapshotInfo.setStatus(StorageProperties.Status.available.toString());
			snapshotInfo.setVbladePid(-1);
			snapshotInfo.setSize(size);
			returnValues.add(vgName);
			returnValues.add(lvName);
			db.add(snapshotInfo);
		}
		db.commit();
		return returnValues;
	}

	public List<String> prepareForTransfer(String volumeId, String snapshotId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
		LVMVolumeInfo foundLVMVolumeInfo = db.getUnique(lvmVolumeInfo);
		ArrayList<String> returnValues = new ArrayList<String>();

		if(foundLVMVolumeInfo != null) {
			returnValues.add(StorageProperties.storageRootDirectory + PATH_SEPARATOR + foundLVMVolumeInfo.getVolumeId());
			db.commit();
		} else {
			db.rollback();
			throw new EucalyptusCloudException("Unable to find snapshot: " + snapshotId);
		}
		return returnValues;
	}

	public void deleteSnapshot(String snapshotId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
		LVMVolumeInfo foundLVMVolumeInfo = db.getUnique(lvmVolumeInfo);

		if(foundLVMVolumeInfo != null) {
			db.delete(foundLVMVolumeInfo);
			db.commit();
		}  else {
			db.rollback();
			throw new EucalyptusCloudException("Unable to find snapshot: " + snapshotId);
		}
	}

	public List<String> getVolume(String volumeId) throws EucalyptusCloudException {
		ArrayList<String> returnValues = new ArrayList<String>();

		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(volumeId);
		List<LVMVolumeInfo> foundLvmVolumeInfos = db.query(lvmVolumeInfo);
		if(foundLvmVolumeInfos.size() > 0) {
			LVMVolumeInfo foundLvmVolumeInfo = foundLvmVolumeInfos.get(0);
			returnValues.add(String.valueOf(foundLvmVolumeInfo.getMajorNumber()));
			returnValues.add(String.valueOf(foundLvmVolumeInfo.getMinorNumber()));
		}
		db.commit();
		return returnValues;
	}

	public void loadSnapshots(List<String> snapshotSet, List<String> snapshotFileNames) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		assert(snapshotSet.size() == snapshotFileNames.size());
		int i = 0;
		for(String snapshotFileName: snapshotFileNames) {
			String loDevName = createLoopback(snapshotFileName);
			LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotSet.get(i++));
			lvmVolumeInfo.setLoDevName(loDevName);
			lvmVolumeInfo.setMajorNumber(-1);
			lvmVolumeInfo.setMinorNumber(-1);
			lvmVolumeInfo.setStatus(StorageProperties.Status.available.toString());
			db.add(lvmVolumeInfo);
		}
		db.commit();
	}

	public void reload() {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo volumeInfo = new LVMVolumeInfo();
		List<LVMVolumeInfo> volumeInfos = db.query(volumeInfo);
		for(LVMVolumeInfo foundVolumeInfo : volumeInfos) {
			String loDevName = foundVolumeInfo.getLoDevName();
			if(loDevName != null) {
				String loFileName = foundVolumeInfo.getVolumeId();
				String absoluteLoFileName = StorageProperties.storageRootDirectory + PATH_SEPARATOR + loFileName;
				String returnValue = getLoopback(loDevName);
				if(returnValue.length() <= 0) {
					createLoopback(absoluteLoFileName, loDevName);
				}
			}
		}
		//now enable them
		for(LVMVolumeInfo foundVolumeInfo : volumeInfos) {
			int pid = foundVolumeInfo.getVbladePid();
			if(pid > 0) {
				//enable logical volumes
				String absoluteLVName = lvmRootDirectory + PATH_SEPARATOR + foundVolumeInfo.getVgName() + PATH_SEPARATOR + foundVolumeInfo.getLvName();
				enableLogicalVolume(absoluteLVName);
				String returnValue = aoeStatus(pid);
				if(returnValue.length() == 0) {
					int majorNumber = foundVolumeInfo.getMajorNumber();
					int minorNumber = foundVolumeInfo.getMinorNumber();
					pid = exportManager.exportVolume(StorageProperties.iface, absoluteLVName, majorNumber, minorNumber);
					foundVolumeInfo.setVbladePid(pid);
					File vbladePidFile = new File(eucaHome + EUCA_VAR_RUN_PATH + "/vblade-" + majorNumber + minorNumber + ".pid");
					try {
						FileOutputStream fileOutStream = new FileOutputStream(vbladePidFile);
						String pidString = String.valueOf(pid);
						fileOutStream.write(pidString.getBytes());
						fileOutStream.close();
					} catch (Exception ex) {
						LOG.error("Could not write pid file vblade-" + majorNumber + minorNumber + ".pid");
					}
				}
			}
		}
		db.commit();
	}

	public List<String> getSnapshotValues(String snapshotId) throws EucalyptusCloudException {
		ArrayList<String> returnValues = new ArrayList<String>();

		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
		List<LVMVolumeInfo> lvmVolumeInfos = db.query(lvmVolumeInfo);
		if(lvmVolumeInfos.size() > 0) {
			LVMVolumeInfo foundLVMVolumeInfo = lvmVolumeInfos.get(0);
			returnValues.add(foundLVMVolumeInfo.getVgName());
			returnValues.add(foundLVMVolumeInfo.getLvName());
		}
		db.commit();
		return returnValues;
	}

	public int getSnapshotSize(String snapshotId) throws EucalyptusCloudException {
		EntityWrapper<LVMVolumeInfo> db = new EntityWrapper<LVMVolumeInfo>();
		LVMVolumeInfo lvmVolumeInfo = new LVMVolumeInfo(snapshotId);
		List<LVMVolumeInfo> lvmVolumeInfos = db.query(lvmVolumeInfo);
		if(lvmVolumeInfos.size() > 0) {
			LVMVolumeInfo foundLVMVolumeInfo = lvmVolumeInfos.get(0);
			int snapSize = foundLVMVolumeInfo.getSize();
			db.commit();
			return snapSize;
		} else {
			db.rollback();
			return 0;
		}
	}

	private String aoeStatus(int pid) {
		File file = new File("/proc/" + pid + "/cmdline");
		String returnString = "";
		if(file.exists()) {
			try {
				FileInputStream fileIn = new FileInputStream(file);
				byte[] bytes = new byte[512];
				int bytesRead;
				while((bytesRead = fileIn.read(bytes)) > 0) {
					returnString += new String(bytes, 0, bytesRead);
				}
			} catch (Exception ex) {
				LOG.warn("could not find " + file.getAbsolutePath());
			}
		}
		return returnString;
	}
}
