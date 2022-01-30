/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.wifi.hostapd.V1_0.HostapdStatus;
import android.hardware.wifi.hostapd.V1_0.HostapdStatusCode;
import android.net.MacAddress;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApConfiguration.BandType;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IHwBinder.DeathRecipient;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.WifiNative.HostapdDeathEventHandler;
import com.android.server.wifi.WifiNative.SoftApHalCallback;

import java.io.PrintWriter;
import java.util.List;

import javax.annotation.concurrent.ThreadSafe;

import vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor;
import vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendorIfaceCallback;

/**
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
public class HostapdHal {
    private static final String TAG = "HostapdHal";

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;
    private boolean mVerboseHalLoggingEnabled = false;
    private final Context mContext;
    private final Handler mEventHandler;

    // Hostapd HAL interface object - might be implemented by HIDL or AIDL
    private IHostapdHal mIHostapd;

    public HostapdHal(Context context, Handler handler) {
        mContext = context;
        mEventHandler = handler;
        mHostapdVendorDeathRecipient = new HostapdVendorDeathRecipient();
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param enable true to enable, false to disable.
     */
    public void enableVerboseLogging(boolean verboseEnabled, boolean halVerboseEnabled) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = verboseEnabled;
            mVerboseHalLoggingEnabled = halVerboseEnabled;
            if (mIHostapd != null) {
                mIHostapd.enableVerboseLogging(verboseEnabled, halVerboseEnabled);
            }
        }
    }

    /**
     * Initialize the HostapdHal. Creates the internal IHostapdHal object
     * and calls its initialize method.
     *
     * @return true if the initialization succeeded
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Initializing Hostapd Service.");
            }
            if (mIHostapd != null) {
                Log.wtf(TAG, "Hostapd HAL has already been initialized.");
                return false;
            }
            mIHostapdVendor = null;
            mIHostapd = createIHostapdHalMockable();
            if (mIHostapd == null) {
                Log.e(TAG, "Failed to get Hostapd HAL instance");
                return false;
            }
            mIHostapd.enableVerboseLogging(mVerboseLoggingEnabled, mVerboseHalLoggingEnabled);
            if (!mIHostapd.initialize()) {
                Log.e(TAG, "Fail to init hostapd, Stopping hostapd startup");
                mIHostapd = null;
                return false;
            }
            // if (!initHostapdVendorService()) {
            //     Log.e(TAG, "Failed to init HostapdVendor service");
            // }
            return true;
        }
    }

    /**
     * Wrapper function to create the IHostapdHal object. Created to be mockable in unit tests.
     */
    @VisibleForTesting
    protected IHostapdHal createIHostapdHalMockable() {
        synchronized (mLock) {
            // Prefer AIDL implementation if service is declared.
            if (HostapdHalAidlImp.serviceDeclared()) {
                Log.i(TAG, "Initializing hostapd using AIDL implementation.");
                return new HostapdHalAidlImp(mContext, mEventHandler);

            } else if (HostapdHalHidlImp.serviceDeclared()) {
                Log.i(TAG, "Initializing hostapd using HIDL implementation.");
                return new HostapdHalHidlImp(mContext, mEventHandler);
            }
            Log.e(TAG, "No HIDL or AIDL service available for hostapd.");
            return null;
        }
    }

    /**
     * Returns whether or not the hostapd supports getting the AP info from the callback.
     */
    public boolean isApInfoCallbackSupported() {
        synchronized (mLock) {
            String methodStr = "isApInfoCallbackSupported";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.isApInfoCallbackSupported() || useVendorHostapdHal();
        }
    }

    /**
     * Register the provided callback handler for SoftAp events.
     * <p>
     * Note that only one callback can be registered at a time - any registration overrides previous
     * registrations.
     *
     * @param ifaceName Name of the interface.
     * @param listener Callback listener for AP events.
     * @return true on success, false on failure.
     */
    public boolean registerApCallback(@NonNull String ifaceName,
            @NonNull SoftApHalCallback callback) {
        synchronized (mLock) {
            String methodStr = "registerApCallback";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }

            if (!isApInfoCallbackSupported()) {
                Log.d(TAG, "The current HAL doesn't support event callback.");
                return false;
            }

            return mIHostapd.registerApCallback(ifaceName, callback);
        }
    }

    /**
     * Add and start a new access point.
     *
     * @param ifaceName Name of the interface.
     * @param config Configuration to use for the AP.
     * @param isMetered Indicates the network is metered or not.
     * @param onFailureListener A runnable to be triggered on failure.
     * @return true on success, false otherwise.
     */
    public boolean addAccessPoint(@NonNull String ifaceName, @NonNull SoftApConfiguration config,
                                  boolean isMetered, @NonNull Runnable onFailureListener) {
        synchronized (mLock) {
            String methodStr = "addAccessPoint";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.addAccessPoint(ifaceName, config, isMetered, onFailureListener);
        }
    }

    /**
     * Remove a previously started access point.
     *
     * @param ifaceName Name of the interface.
     * @return true on success, false otherwise.
     */
    public boolean removeAccessPoint(@NonNull String ifaceName) {
        synchronized (mLock) {
            String methodStr = "removeAccessPoint";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.removeAccessPoint(ifaceName);
        }
    }

    /**
     * Remove a previously connected client.
     *
     * @param ifaceName Name of the interface.
     * @param client Mac Address of the client.
     * @param reasonCode One of disconnect reason code which defined in {@link WifiManager}.
     * @return true on success, false otherwise.
     */
    public boolean forceClientDisconnect(@NonNull String ifaceName,
            @NonNull MacAddress client, int reasonCode) {
        synchronized (mLock) {
            String methodStr = "forceClientDisconnect";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.forceClientDisconnect(ifaceName, client, reasonCode);
        }
    }

    /**
     * Registers a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean registerDeathHandler(@NonNull HostapdDeathEventHandler handler) {
        synchronized (mLock) {
            String methodStr = "registerDeathHandler";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.registerDeathHandler(handler);
        }
    }

    /**
     * Deregisters a death notification for hostapd.
     * @return Returns true on success.
     */
    public boolean deregisterDeathHandler() {
        synchronized (mLock) {
            String methodStr = "deregisterDeathHandler";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.deregisterDeathHandler();
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            String methodStr = "isInitializationStarted";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.isInitializationStarted();
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            String methodStr = "isInitializationComplete";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.isInitializationComplete();
        }
    }

    /**
     * Start the hostapd daemon.
     *
     * @return true on success, false otherwise.
     */
    public boolean startDaemon() {
        synchronized (mLock) {
            String methodStr = "startDaemon";
            if (mIHostapd == null) {
                return handleNullIHostapd(methodStr);
            }
            return mIHostapd.startDaemon();
        }
    }

    /**
     * Terminate the hostapd daemon & wait for it's death.
     */
    public void terminate() {
        synchronized (mLock) {
            String methodStr = "terminate";
            if (mIHostapd == null) {
                handleNullIHostapd(methodStr);
                return;
            }
            mIHostapd.terminate();
        }
    }

    private boolean handleNullIHostapd(String methodStr) {
        Log.e(TAG, "Cannot call " + methodStr + " because mIHostapd is null.");
        return false;
    }

    protected void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("Dump of HostapdHal");
            pw.println("AIDL service declared: " + HostapdHalAidlImp.serviceDeclared());
            pw.println("HIDL service declared: " + HostapdHalHidlImp.serviceDeclared());
            boolean initialized = mIHostapd != null;
            pw.println("Initialized: " + initialized);
            if (initialized) {
                pw.println("Implementation: " + mIHostapd.getClass().getSimpleName());
                mIHostapd.dump(pw);
            }
        }
    }

    /* ######################### Hostapd Vendor change ###################### */
    // Keep hostapd vendor changes below this line to have minimal conflicts during merge/upgrade
    private IHostapdVendor mIHostapdVendor;
    private HostapdVendorDeathRecipient mHostapdVendorDeathRecipient;

    private class HostapdVendorDeathRecipient implements DeathRecipient {
        @Override
        public void serviceDied(long cookie) {
            mEventHandler.post(() -> {
                synchronized (mLock) {
                    Log.w(TAG, "IHostapdVendor died: cookie=" + cookie);
                    // TODO(b/203794430) The following is moved to HostapdHalAidlImp.java
                    // hostapdServiceDiedHandler(cookie);
                }
            });
        }
    }

    /**
     * Helper method for Get Vendor Encryption Type.
     */
    private static int getVendorEncryptionType(SoftApConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getSecurityType()) {
            case SoftApConfiguration.SECURITY_TYPE_OPEN:
                encryptionType =
                  vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorEncryptionType.NONE;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                encryptionType =
                  vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorEncryptionType.WPA2;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE:
                encryptionType =
                  vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorEncryptionType.SAE;
                break;
            case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION:
                encryptionType =
                  vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorEncryptionType.SAE_TRANSITION;
                break;
            case SoftApConfiguration.SECURITY_TYPE_OWE:
                encryptionType = vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorEncryptionType.OWE;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType =
                  vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorEncryptionType.NONE;
                break;
        }
        return encryptionType;
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_0 of
     * the hostapd vendor HAL from the VINTF for the device.
     * @return true if supported, false otherwise.
     */
    private boolean isVendorV1_0() {
        return checkHalVersionByInterfaceName(
                vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor.kInterfaceName);
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_1 of
     * the hostapd vendor HAL from the VINTF for the device.
     * @return true if supported, false otherwise.
     */
    private boolean isVendorV1_1() {
        return checkHalVersionByInterfaceName(
                vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.kInterfaceName);
    }

    /**
     * Uses the IServiceManager to check if the device is running V1_2 of
     * the hostapd vendor HAL from the VINTF for the device.
     * @return true if supported, false otherwise.
     */
    private boolean isVendorV1_2() {
        return checkHalVersionByInterfaceName(
                vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.kInterfaceName);
    }

    private boolean checkHalVersionByInterfaceName(String interfaceName) {
        //FIXME
        return false;
    }

    // TODO(b/203689430): QC added code is commented out
    // /**
    //  * Link to death for IHostapdVendor object.
    //  * @return true on success, false otherwise.
    //  */
    // private boolean linkToHostapdVendorDeath() {
    //     synchronized (mLock) {
    //         if (mIHostapdVendor == null) return false;
    //         try {
    //             if (!mIHostapdVendor.linkToDeath(
    //                     mHostapdVendorDeathRecipient, mDeathRecipientCookie)) {
    //                 Log.wtf(TAG, "Error on linkToDeath on IHostapdVendor");
    //                 hostapdServiceDiedHandler(mDeathRecipientCookie);
    //                 return false;
    //             }
    //         } catch (RemoteException e) {
    //             Log.e(TAG, "IHostapdVendor.linkToDeath exception", e);
    //             return false;
    //         }
    //         return true;
    //     }
    // }

    // /**
    //  * Initialize the IHostapdVendor object.
    //  * @return true on success, false otherwise.
    //  */
    // public boolean initHostapdVendorService() {
    //     synchronized (mLock) {
    //         try {
    //             mIHostapdVendor = getHostapdVendorMockable();
    //         } catch (RemoteException e) {
    //             Log.e(TAG, "IHostapdVendor.getService exception: " + e);
    //             return false;
    //         }
    //         if (mIHostapdVendor == null) {
    //             Log.e(TAG, "Got null IHostapdVendor service. Stopping hostapdVendor HIDL startup");
    //             return false;
    //         }
    //         if (!linkToHostapdVendorDeath()) {
    //             mIHostapdVendor = null;
    //             return false;
    //         }
    //     }
    //     return true;
    // }

    // /**
    //  * Wrapper to Convert IHostapd.AcsFrequencyRange to IHostapdVendor.AcsFrequencyRange
    //  */
    // private List<vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.AcsFrequencyRange>
    //         toVendorAcsFreqRanges(@BandType int band) {
    //     List<android.hardware.wifi.hostapd.V1_2.IHostapd.AcsFrequencyRange>
    //             acsFrequencyRanges = toAcsFreqRanges(band);

    //     List<vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.AcsFrequencyRange>
    //             vendorAcsFreqRanges = new ArrayList<>();

    //     for (android.hardware.wifi.hostapd.V1_2.IHostapd.AcsFrequencyRange
    //             acsFrequencyRange : acsFrequencyRanges) {
    //         vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.AcsFrequencyRange acsFreqRange =
    //                 new vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.AcsFrequencyRange();
    //         acsFreqRange.start = acsFrequencyRange.start;
    //         acsFreqRange.end = acsFrequencyRange.end;
    //         vendorAcsFreqRanges.add(acsFreqRange);
    //     }

    //     return vendorAcsFreqRanges;
    // }

    // // Implementation refactors from class HostapdHal::updateIfaceParams_1_2FromResource()
    // private void updateVendorIfaceParams_1_2FromResource(
    //   vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorIfaceParams vIfaceParamsV1_2) {
    //     vIfaceParamsV1_2.hwModeParams.enable80211AX =
    //             mContext.getResources().getBoolean(
    //             R.bool.config_wifiSoftapIeee80211axSupported);
    //     vIfaceParamsV1_2.hwModeParams.enable6GhzBand =
    //             ApConfigUtil.isBandSupported(SoftApConfiguration.BAND_6GHZ, mContext);
    //     vIfaceParamsV1_2.hwModeParams.enableHeSingleUserBeamformer =
    //             mContext.getResources().getBoolean(
    //             R.bool.config_wifiSoftapHeSuBeamformerSupported);
    //     vIfaceParamsV1_2.hwModeParams.enableHeSingleUserBeamformee =
    //             mContext.getResources().getBoolean(
    //             R.bool.config_wifiSoftapHeSuBeamformeeSupported);
    //     vIfaceParamsV1_2.hwModeParams.enableHeMultiUserBeamformer =
    //             mContext.getResources().getBoolean(
    //             R.bool.config_wifiSoftapHeMuBeamformerSupported);
    //     vIfaceParamsV1_2.hwModeParams.enableHeTargetWakeTime =
    //             mContext.getResources().getBoolean(R.bool.config_wifiSoftapHeTwtSupported);
    // }

    // private vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor.VendorIfaceParams
    // prepareVendorIfaceParamsV1_0(
    //   android.hardware.wifi.hostapd.V1_0.IHostapd.IfaceParams ifaceParamsV1_0,
    //   SoftApConfiguration config) {
    //     vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor.VendorIfaceParams
    //     vIfaceParamsV1_0 =
    //       new vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor.VendorIfaceParams();
    //     vIfaceParamsV1_0.ifaceParams = ifaceParamsV1_0;

    //     // Vendor Hostapd V1_0 specific parameters
    //     WifiNative wifiNative = WifiInjector.getInstance().getWifiNative();
    //     if (wifiNative.isVendorBridgeModeActive()) {
    //         vIfaceParamsV1_0.bridgeIfaceName = wifiNative.getBridgeIfaceName();
    //     } else {
    //         vIfaceParamsV1_0.bridgeIfaceName = "";
    //     }

    //     return vIfaceParamsV1_0;
    // }

    // private vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorIfaceParams
    // prepareVendorIfaceParamsV1_1(
    //   vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor.VendorIfaceParams vIfaceParamsV1_0,
    //   SoftApConfiguration config) {
    //     vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorIfaceParams
    //     vIfaceParamsV1_1 =
    //       new vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorIfaceParams();
    //     vIfaceParamsV1_1.VendorV1_0 = vIfaceParamsV1_0;
    //     vIfaceParamsV1_1.vendorChannelParams.channelParams =
    //       vIfaceParamsV1_0.ifaceParams.channelParams;
    //     vIfaceParamsV1_1.vendorEncryptionType = getVendorEncryptionType(config);
    //     vIfaceParamsV1_1.oweTransIfaceName = (config.getOweTransIfaceName() != null) ? config.getOweTransIfaceName() : "";
    //     if (vIfaceParamsV1_0.ifaceParams.channelParams.enableAcs) {
    //         if ((config.getBand() & SoftApConfiguration.BAND_2GHZ) != 0) {
    //             vIfaceParamsV1_1.vendorChannelParams.acsChannelRanges.addAll(
    //                     toVendorAcsChannelRanges(mContext.getResources().getString(
    //                         R.string.config_wifiSoftap2gChannelList)));
    //         }
    //         if ((config.getBand() & SoftApConfiguration.BAND_5GHZ) != 0) {
    //             vIfaceParamsV1_1.vendorChannelParams.acsChannelRanges.addAll(
    //                     toVendorAcsChannelRanges(mContext.getResources().getString(
    //                         R.string.config_wifiSoftap5gChannelList)));
    //         }
    //     }
    //     return vIfaceParamsV1_1;
    // }

    // private vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorIfaceParams
    // prepareVendorIfaceParamsV1_2(
    //   vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorIfaceParams vIfaceParamsV1_1,
    //   SoftApConfiguration config) {
    //     vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorIfaceParams
    //     vIfaceParamsV1_2 =
    //       new vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorIfaceParams();
    //     vIfaceParamsV1_2.VendorV1_1 = vIfaceParamsV1_1;
    //     vIfaceParamsV1_2.channelParams.bandMask = getHalBandMask(config.getBand());

    //     updateVendorIfaceParams_1_2FromResource(vIfaceParamsV1_2);

    //     // Prepare freq ranges/lists if needed
    //     if (vIfaceParamsV1_1.VendorV1_0.ifaceParams.channelParams.enableAcs
    //          && ApConfigUtil.isSendFreqRangesNeeded(config.getBand(), mContext)) {
    //         if ((config.getBand() & SoftApConfiguration.BAND_2GHZ) != 0) {
    //             vIfaceParamsV1_2.channelParams.acsChannelFreqRangesMhz.addAll(
    //                 toVendorAcsFreqRanges(SoftApConfiguration.BAND_2GHZ));
    //         }
    //         if ((config.getBand() & SoftApConfiguration.BAND_5GHZ) != 0) {
    //             vIfaceParamsV1_2.channelParams.acsChannelFreqRangesMhz.addAll(
    //                 toVendorAcsFreqRanges(SoftApConfiguration.BAND_5GHZ));
    //         }
    //         if ((config.getBand() & SoftApConfiguration.BAND_6GHZ) != 0) {
    //             vIfaceParamsV1_2.channelParams.acsChannelFreqRangesMhz.addAll(
    //                 toVendorAcsFreqRanges(SoftApConfiguration.BAND_6GHZ));
    //         }
    //     }
    //     return vIfaceParamsV1_2;
    // }

    // private vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorNetworkParams
    //         prepareVendorNetworkParamsV1_2(
    //           android.hardware.wifi.hostapd.V1_2.IHostapd.NetworkParams nwParamsV1_2,
    //           SoftApConfiguration config) {
    //     vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorNetworkParams
    //     vNetworkParamsV1_2 =
    //       new vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorNetworkParams();
    //     vNetworkParamsV1_2.V1_0 = nwParamsV1_2.V1_0;
    //     vNetworkParamsV1_2.passphrase = nwParamsV1_2.passphrase;
    //     vNetworkParamsV1_2.vendorEncryptionType = getVendorEncryptionType(config);
    //     vNetworkParamsV1_2.enableOCV = mContext.getResources().getBoolean(
    //             R.bool.config_vendor_softap_ocv_supported);
    //     vNetworkParamsV1_2.enableBeaconProtection = mContext.getResources().getBoolean(
    //             R.bool.config_vendor_softap_beacon_protection_supported);
    //     return vNetworkParamsV1_2;
    // }

    // /**
    //  * Add and start a new vendor access point.
    //  *
    //  * @param ifaceName Name of the softap interface.
    //  * @param config Configuration to use for the AP.
    //  * @param onFailureListener A runnable to be triggered on failure.
    //  * @return true on success, false otherwise.
    //  */
    public boolean addVendorAccessPoint(@NonNull String ifaceName,
            @NonNull SoftApConfiguration config, @NonNull Runnable onFailureListener) {
        return false;
    }
    //    Log.i(TAG, "addVendorAccessPoint[" + ifaceName + "] channels=" + config.getChannels());

    //    synchronized (mLock) {
    //         final String methodStr = "addVendorAccessPoint";
    //         IHostapd.IfaceParams ifaceParamsV1_0 = prepareIfaceParamsV1_0(ifaceName, config);
    //         android.hardware.wifi.hostapd.V1_2.IHostapd.NetworkParams nwParamsV1_2 =
    //                 prepareNetworkParamsV1_2(config);
    //         if (nwParamsV1_2 == null) return false;
    //         if (!checkHostapdVendorAndLogFailure(methodStr)) return false;
    //         try {
    //             HostapdStatus status;

    //             vendor.qti.hardware.wifi.hostapd.V1_0.IHostapdVendor.VendorIfaceParams
    //             vIfaceParamsV1_0 = prepareVendorIfaceParamsV1_0(
    //                     ifaceParamsV1_0, config);
    //             if (!isVendorV1_1()) {
    //                 // vendor V1_0 case
    //                 if (!registerVendorCallback(ifaceName,
    //                         new HostapdVendorIfaceHalCallback(ifaceName))) {
    //                     Log.e(TAG, "Failed to register Hostapd Vendor callback");
    //                     return false;
    //                 }
    //                 status = mIHostapdVendor.addVendorAccessPoint(
    //                         vIfaceParamsV1_0, nwParamsV1_2.V1_0);
    //                 if (!checkVendorStatusAndLogFailure(status, methodStr)) {
    //                     return false;
    //                 }
    //             } else {
    //                 vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.VendorIfaceParams
    //                 vIfaceParamsV1_1 = prepareVendorIfaceParamsV1_1(
    //                         vIfaceParamsV1_0, config);
    //                 if (!isVendorV1_2()) {
    //                     // vendor V1_1 case
    //                     if (!registerVendorCallback_1_1(ifaceName,
    //                              new HostapdVendorIfaceHalCallbackV1_1(ifaceName))) {
    //                         Log.e(TAG, "Failed to register Hostapd Vendor V1_1.callback");
    //                         return false;
    //                     }
    //                     vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor
    //                     iHostapdVendorV1_1 = getHostapdVendorMockableV1_1();
    //                     if (iHostapdVendorV1_1 == null) {
    //                         Log.e(TAG, "Failed to get V1_1.IHostapdVendor");
    //                         return false;
    //                     }
    //                     status = iHostapdVendorV1_1.addVendorAccessPoint_1_1(
    //                         vIfaceParamsV1_1, nwParamsV1_2.V1_0);
    //                     if (!checkVendorStatusAndLogFailure(status, methodStr)) {
    //                         return false;
    //                     }
    //                 } else {
    //                     // vendor V1_2 case
    //                     if (!registerVendorCallback_1_1(ifaceName,
    //                              new HostapdVendorIfaceHalCallbackV1_1(ifaceName))) {
    //                         Log.e(TAG, "Failed to register Hostapd Vendor V1_1.callback");
    //                         return false;
    //                     }
    //                     vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorIfaceParams
    //                     vIfaceParamsV1_2 = prepareVendorIfaceParamsV1_2(
    //                             vIfaceParamsV1_1, config);
    //                     vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.VendorNetworkParams
    //                     vNetworkParamsV1_2 = prepareVendorNetworkParamsV1_2(
    //                              nwParamsV1_2, config);

    //                     vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor
    //                     iHostapdVendorV1_2 = getHostapdVendorMockableV1_2();
    //                     if (iHostapdVendorV1_2 == null) {
    //                         Log.e(TAG, "Failed to get V1_2.IHostapdVendor");
    //                         return false;
    //                     }
    //                     status = iHostapdVendorV1_2.addVendorAccessPoint_1_2(
    //                         vIfaceParamsV1_2, vNetworkParamsV1_2);
    //                     if (!checkVendorStatusAndLogFailure(status, methodStr)) {
    //                         return false;
    //                     }
    //                 }
    //             }

    //             mSoftApFailureListeners.put(ifaceName, onFailureListener);
    //             return true;
    //         } catch (IllegalArgumentException e) {
    //             Log.e(TAG, "Unrecognized apBand: " + config.getBand());
    //             return false;
    //         } catch (RemoteException e) {
    //             handleRemoteException(e, methodStr);
    //             return false;
    //         }
    //     }
    // }

    // @VisibleForTesting
    // protected IHostapdVendor getHostapdVendorMockable() throws RemoteException {
    //     synchronized (mLock) {
    //         return IHostapdVendor.getService();
    //     }
    // }

    // @VisibleForTesting
    // protected vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor getHostapdVendorMockableV1_1()
    //         throws RemoteException {
    //     synchronized (mLock) {
    //         try {
    //             return
    //               vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.castFrom(mIHostapdVendor);
    //         } catch (NoSuchElementException e) {
    //             Log.e(TAG, "Failed to get IHostapdVendorV1_1", e);
    //             return null;
    //         }
    //     }
    // }

    // @VisibleForTesting
    // protected vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor getHostapdVendorMockableV1_2()
    //         throws RemoteException {
    //     synchronized (mLock) {
    //         try {
    //             return
    //               vendor.qti.hardware.wifi.hostapd.V1_2.IHostapdVendor.castFrom(mIHostapdVendor);
    //         } catch (NoSuchElementException e) {
    //             Log.e(TAG, "Failed to get IHostapdVendorV1_2", e);
    //             return null;
    //         }
    //     }
    // }

    // // Implementation refactors from class HostapdHal::toAcsChannelRanges()
    // private List<vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.AcsChannelRange>
    //         toVendorAcsChannelRanges(String channelListStr) {
    //     ArrayList<vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.AcsChannelRange>
    //     acsChannelRanges = new ArrayList<>();

    //     for (String channelRange : channelListStr.split(",")) {
    //         vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.AcsChannelRange
    //         acsChannelRange =
    //           new vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor.AcsChannelRange();
    //         try {
    //             if (channelRange.contains("-")) {
    //                 String[] channels  = channelRange.split("-");
    //                 if (channels.length != 2) {
    //                     Log.e(TAG, "Unrecognized channel range, length is " + channels.length);
    //                     continue;
    //                 }
    //                 int start = Integer.parseInt(channels[0].trim());
    //                 int end = Integer.parseInt(channels[1].trim());
    //                 if (start > end) {
    //                     Log.e(TAG, "Invalid channel range, from " + start + " to " + end);
    //                     continue;
    //                 }
    //                 acsChannelRange.start = start;
    //                 acsChannelRange.end = end;
    //             } else {
    //                 acsChannelRange.start = Integer.parseInt(channelRange.trim());
    //                 acsChannelRange.end = acsChannelRange.start;
    //             }
    //         } catch (NumberFormatException e) {
    //             // Ignore malformed value
    //             Log.e(TAG, "Malformed channel value detected: " + e);
    //             continue;
    //         }
    //         acsChannelRanges.add(acsChannelRange);
    //     }
    //     return acsChannelRanges;
    // }

    // /**
    //  * Check if needs to use hostapd vendor service.
    //  * @return
    //  */
    public boolean useVendorHostapdHal() {
        return false;
    }
    //     // Validates hostapd vendor service initialized.
    //     if (mIHostapdVendor == null) {
    //         return false;
    //     }

    //     // Refer Vendor Hal release - current.txt
    //     if (isVendorV1_2() && !isV1_3()) {
    //         // Vendor V1_2(R) > 1_2(R) or ealier
    //         return true;
    //     } else if (isVendorV1_1() && !isV1_2() ) {
    //         // Vendor V1_1(Q) > 1_1(Q) or ealier
    //         return true;
    //     } else if (isVendorV1_0() && !isV1_1()) {
    //         // Vendor V1_0(P) > 1_0(P)
    //         return true;
    //     }

    //     return false;
    // }

    // /**
    //  * Returns false if HostapdVendor is null, and logs failure to call methodStr
    //  */
    // private boolean checkHostapdVendorAndLogFailure(String methodStr) {
    //     synchronized (mLock) {
    //         if (mIHostapdVendor == null) {
    //             Log.e(TAG, "Can't call " + methodStr + ", IHostapdVendor is null");
    //             return false;
    //         }
    //         return true;
    //     }
    // }

    // /**
    //  * Returns true if provided status code is SUCCESS, logs debug message and returns false
    //  * otherwise
    //  */
    // private boolean checkVendorStatusAndLogFailure(HostapdStatus status,
    //         String methodStr) {
    //     synchronized (mLock) {
    //         if (status.code != HostapdStatusCode.SUCCESS) {
    //             Log.e(TAG, "IHostapdVendor." + methodStr + " failed: " + status.code
    //                     + ", " + status.debugMessage);
    //             return false;
    //         } else {
    //             if (mVerboseLoggingEnabled) {
    //                 Log.e(TAG, "IHostapdVendor." + methodStr + " succeeded");
    //             }
    //             return true;
    //         }
    //     }
    // }

    // // Implementation refactors from class HostapdCallback_1_3::onConnectedClientsChanged()
    // private void notifyConnectedClientsChanged(
    //   String ifaceName, byte[/* 6 */] bssid, boolean isConnected) {
    //     if (bssid == null) return;

    //     String apIfaceInstance = ifaceName;
    //     WifiNative wifiNative = WifiInjector.getInstance().getWifiNative();
    //     if (wifiNative.isVendorBridgeModeActive()) {
    //         apIfaceInstance = wifiNative.getBridgeIfaceName();
    //     }

    //     try {
    //         Log.d(TAG, "notifyConnectedClientsChanged on " + ifaceName + " / " + apIfaceInstance
    //                + " and Mac is " + MacAddress.fromBytes(bssid).toString()
    //                + " isConnected: " + isConnected);
    //         if (mSoftApEventListener != null) {
    //             mSoftApEventListener.onConnectedClientsChanged(apIfaceInstance,
    //                 MacAddress.fromBytes(bssid), isConnected);
    //         }
    //     } catch (IllegalArgumentException iae) {
    //         Log.e(TAG, " Invalid clientAddress, " + iae);
    //     }
    // }

    // private class HostapdVendorIfaceHalCallback extends IHostapdVendorIfaceCallback.Stub {
    //     private String apIfaceName;

    //     HostapdVendorIfaceHalCallback(@NonNull String ifaceName) {
    //        apIfaceName = ifaceName;
    //     }

    //     @Override
    //     public void onStaConnected(byte[/* 6 */] bssid) {
    //         notifyConnectedClientsChanged(apIfaceName, bssid, true);
    //     }

    //     @Override
    //     public void onStaDisconnected(byte[/* 6 */] bssid) {
    //         notifyConnectedClientsChanged(apIfaceName, bssid, false);
    //     }
    // }

    // /** See IHostapdVendor.hal for documentation */
    // private boolean registerVendorCallback(@NonNull String ifaceName,
    //         IHostapdVendorIfaceCallback callback) {
    //     synchronized (mLock) {
    //         final String methodStr = "registerVendorCallback";
    //         try {
    //             if (mIHostapdVendor == null) return false;
    //             HostapdStatus status =
    //                     mIHostapdVendor.registerVendorCallback(ifaceName, callback);
    //             return checkVendorStatusAndLogFailure(status, methodStr);
    //         } catch (RemoteException e) {
    //             handleRemoteException(e, methodStr);
    //             return false;
    //         }
    //     }
    // }

    // private class HostapdVendorIfaceHalCallbackV1_1 extends
    //         vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendorIfaceCallback.Stub {
    //     private String apIfaceName;

    //     HostapdVendorIfaceHalCallbackV1_1(@NonNull String ifaceName) {
    //        apIfaceName = ifaceName;
    //     }

    //     @Override
    //     public void onStaConnected(byte[/* 6 */] bssid) {
    //         notifyConnectedClientsChanged(apIfaceName, bssid, true);
    //     }

    //     @Override
    //     public void onStaDisconnected(byte[/* 6 */] bssid) {
    //         notifyConnectedClientsChanged(apIfaceName, bssid, false);
    //     }

    //     @Override
    //     public void onFailure(String ifaceName) {
    //         Log.w(TAG, "Failure on iface " + ifaceName);
    //         Runnable onFailureListener = mSoftApFailureListeners.get(ifaceName);
    //         if (onFailureListener != null) {
    //             onFailureListener.run();
    //         }
    //     }
    // }

    // private boolean registerVendorCallback_1_1(@NonNull String ifaceName,
    //         vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendorIfaceCallback callback) {
    //     synchronized (mLock) {
    //         String methodStr = "registerVendorCallback_1_1";
    //         try {
    //             vendor.qti.hardware.wifi.hostapd.V1_1.IHostapdVendor iHostapdVendorV1_1 =
    //                 getHostapdVendorMockableV1_1();
    //             if (iHostapdVendorV1_1 == null) return false;
    //             HostapdStatus status =  iHostapdVendorV1_1.registerVendorCallback_1_1(
    //                 ifaceName, callback);
    //             return checkVendorStatusAndLogFailure(status, methodStr);
    //         } catch (RemoteException e) {
    //             handleRemoteException(e, methodStr);
    //             return false;
    //         }
    //     }
    // }
}
