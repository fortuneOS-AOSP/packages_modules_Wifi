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
import android.net.wifi.IApInterface;
import android.net.wifi.IApInterfaceEventCallback;
import android.net.wifi.IClientInterface;
import android.net.wifi.IPnoScanEvent;
import android.net.wifi.IScanEvent;
import android.net.wifi.IWifiScannerImpl;
import android.net.wifi.IWificond;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiSsid;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.NativeUtil;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.wificond.ChannelSettings;
import com.android.server.wifi.wificond.HiddenNetwork;
import com.android.server.wifi.wificond.NativeScanResult;
import com.android.server.wifi.wificond.PnoNetwork;
import com.android.server.wifi.wificond.PnoSettings;
import com.android.server.wifi.wificond.SingleScanSettings;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Set;

/**
 * This class provides methods for WifiNative to send control commands to wificond.
 * NOTE: This class should only be used from WifiNative.
 */
public class WificondControl implements IBinder.DeathRecipient {
    private boolean mVerboseLoggingEnabled = false;

    private static final String TAG = "WificondControl";

    /* Get scan results for a single scan */
    public static final int SCAN_TYPE_SINGLE_SCAN = 0;

    /* Get scan results for Pno Scan */
    public static final int SCAN_TYPE_PNO_SCAN = 1;

    private WifiInjector mWifiInjector;
    private WifiMonitor mWifiMonitor;
    private final CarrierNetworkConfig mCarrierNetworkConfig;

    // Cached wificond binder handlers.
    private IWificond mWificond;
    private IClientInterface mClientInterface;
    private IApInterface mApInterface;
    private IWifiScannerImpl mWificondScanner;
    private IScanEvent mScanEventHandler;
    private IPnoScanEvent mPnoScanEventHandler;
    private IApInterfaceEventCallback mApInterfaceListener;
    private WifiNative.WificondDeathEventHandler mDeathEventHandler;

    private String mClientInterfaceName;


    private class ScanEventHandler extends IScanEvent.Stub {
        @Override
        public void OnScanResultReady() {
            Log.d(TAG, "Scan result ready event");
            mWifiMonitor.broadcastScanResultEvent(mClientInterfaceName);
        }

        @Override
        public void OnScanFailed() {
            Log.d(TAG, "Scan failed event");
            mWifiMonitor.broadcastScanFailedEvent(mClientInterfaceName);
        }
    }

    WificondControl(WifiInjector wifiInjector, WifiMonitor wifiMonitor,
            CarrierNetworkConfig carrierNetworkConfig) {
        mWifiInjector = wifiInjector;
        mWifiMonitor = wifiMonitor;
        mCarrierNetworkConfig = carrierNetworkConfig;
    }

    private class PnoScanEventHandler extends IPnoScanEvent.Stub {
        @Override
        public void OnPnoNetworkFound() {
            Log.d(TAG, "Pno scan result event");
            mWifiMonitor.broadcastPnoScanResultEvent(mClientInterfaceName);
            mWifiInjector.getWifiMetrics().incrementPnoFoundNetworkEventCount();
        }

        @Override
        public void OnPnoScanFailed() {
            Log.d(TAG, "Pno Scan failed event");
            mWifiInjector.getWifiMetrics().incrementPnoScanFailedCount();
        }

        @Override
        public void OnPnoScanOverOffloadStarted() {
            Log.d(TAG, "Pno scan over offload started");
            mWifiInjector.getWifiMetrics().incrementPnoScanStartedOverOffloadCount();
        }

        @Override
        public void OnPnoScanOverOffloadFailed(int reason) {
            Log.d(TAG, "Pno scan over offload failed");
            mWifiInjector.getWifiMetrics().incrementPnoScanFailedOverOffloadCount();
        }
    }

    /**
     * Listener for AP Interface events.
     */
    private class ApInterfaceEventCallback extends IApInterfaceEventCallback.Stub {
        private SoftApListener mSoftApListener;

        ApInterfaceEventCallback(SoftApListener listener) {
            mSoftApListener = listener;
        }

        @Override
        public void onNumAssociatedStationsChanged(int numStations) {
            mSoftApListener.onNumAssociatedStationsChanged(numStations);
        }
    }

    /**
     * Called by the binder subsystem upon remote object death.
     * Invoke all the register death handlers and clear state.
     */
    @Override
    public void binderDied() {
        Log.e(TAG, "Wificond died!");
        if (mDeathEventHandler != null) {
            mDeathEventHandler.onDeath();
        }
        clearState();
        // Invalidate the global wificond handle on death. Will be refereshed
        // on the next setup call.
        mWificond = null;
    }

    /** Enable or disable verbose logging of WificondControl.
     *  @param enable True to enable verbose logging. False to disable verbose logging.
     */
    public void enableVerboseLogging(boolean enable) {
        mVerboseLoggingEnabled = enable;
    }

    /**
     * Registers a death notification for wificond.
     * @return Returns true on success.
     */
    public boolean registerDeathHandler(@NonNull WifiNative.WificondDeathEventHandler handler) {
        if (mDeathEventHandler != null) {
            Log.e(TAG, "Death handler already present");
            return false;
        }
        mDeathEventHandler = handler;
        return true;
    }

    /**
     * Deregisters a death notification for wificond.
     * @return Returns true on success.
     */
    public boolean deregisterDeathHandler() {
        if (mDeathEventHandler == null) {
            Log.e(TAG, "No Death handler present");
            return false;
        }
        mDeathEventHandler = null;
        return true;
    }

    /**
     * Helper method to retrieve the global wificond handle and register for
     * death notifications.
     */
    private boolean retrieveWificondAndRegisterForDeath() {
        if (mWificond != null) {
            Log.d(TAG, "Wificond handle already retrieved");
            // We already have a wificond handle.
            return true;
        }
        mWificond = mWifiInjector.makeWificond();
        if (mWificond == null) {
            Log.e(TAG, "Failed to get reference to wificond");
            return false;
        }
        try {
            mWificond.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to register death notification for wificond");
            // The remote has already died.
            return false;
        }
        return true;
    }

    /**
    * Setup interface for client mode via wificond.
    * @return An IClientInterface as wificond client interface binder handler.
    * Returns null on failure.
    */
    public IClientInterface setupInterfaceForClientMode(@NonNull String ifaceName) {
        Log.d(TAG, "Setting up interface for client mode");
        if (!retrieveWificondAndRegisterForDeath()) {
            return null;
        }

        IClientInterface clientInterface = null;
        try {
            clientInterface = mWificond.createClientInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IClientInterface due to remote exception");
            return null;
        }

        if (clientInterface == null) {
            Log.e(TAG, "Could not get IClientInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(clientInterface.asBinder());

        // Refresh Handlers
        mClientInterface = clientInterface;
        try {
            mClientInterfaceName = clientInterface.getInterfaceName();
            mWificondScanner = mClientInterface.getWifiScannerImpl();
            if (mWificondScanner == null) {
                Log.e(TAG, "Failed to get WificondScannerImpl");
                return null;
            }
            Binder.allowBlocking(mWificondScanner.asBinder());
            mScanEventHandler = new ScanEventHandler();
            mWificondScanner.subscribeScanEvents(mScanEventHandler);
            mPnoScanEventHandler = new PnoScanEventHandler();
            mWificondScanner.subscribePnoScanEvents(mPnoScanEventHandler);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to refresh wificond scanner due to remote exception");
        }

        return clientInterface;
    }

    /**
     * Teardown a specific STA interface configured in wificond.
     *
     * @return Returns true on success.
     */
    public boolean tearDownClientInterface(@NonNull String ifaceName) {
        boolean success;
        try {
            if (mWificondScanner != null) {
                mWificondScanner.unsubscribeScanEvents();
                mWificondScanner.unsubscribePnoScanEvents();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to unsubscribe wificond scanner due to remote exception");
            return false;
        }

        try {
            success = mWificond.tearDownClientInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to teardown client interface due to remote exception");
            return false;
        }
        if (!success) {
            Log.e(TAG, "Failed to teardown client interface");
            return false;
        }

        mClientInterface = null;
        mWificondScanner = null;
        mPnoScanEventHandler = null;
        mScanEventHandler = null;
        return true;
    }

    /**
    * Setup interface for softAp mode via wificond.
    * @return An IApInterface as wificond Ap interface binder handler.
    * Returns null on failure.
    */
    public IApInterface setupInterfaceForSoftApMode(@NonNull String ifaceName) {
        Log.d(TAG, "Setting up interface for soft ap mode");
        if (!retrieveWificondAndRegisterForDeath()) {
            return null;
        }

        IApInterface apInterface = null;
        try {
            apInterface = mWificond.createApInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to get IApInterface due to remote exception");
            return null;
        }

        if (apInterface == null) {
            Log.e(TAG, "Could not get IApInterface instance from wificond");
            return null;
        }
        Binder.allowBlocking(apInterface.asBinder());

        // Refresh Handlers
        mApInterface = apInterface;

        return apInterface;
    }

    /**
     * Teardown a specific AP interface configured in wificond.
     *
     * @return Returns true on success.
     */
    public boolean tearDownSoftApInterface(@NonNull String ifaceName) {
        boolean success;
        try {
            success = mWificond.tearDownApInterface(ifaceName);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to teardown AP interface due to remote exception");
            return false;
        }
        if (!success) {
            Log.e(TAG, "Failed to teardown AP interface");
            return false;
        }
        mApInterface = null;
        mApInterfaceListener = null;
        return true;
    }

    /**
    * Teardown all interfaces configured in wificond.
    * @return Returns true on success.
    */
    public boolean tearDownInterfaces() {
        Log.d(TAG, "tearing down interfaces in wificond");
        // Explicitly refresh the wificodn handler because |tearDownInterfaces()|
        // could be used to cleanup before we setup any interfaces.
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }

        try {
            if (mWificondScanner != null) {
                mWificondScanner.unsubscribeScanEvents();
                mWificondScanner.unsubscribePnoScanEvents();
            }
            mWificond.tearDownInterfaces();
            clearState();
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to tear down interfaces due to remote exception");
        }

        return false;
    }

    /**
    * Disable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean disableSupplicant() {
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }
        try {
            return mWificond.disableSupplicant();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to disable supplicant due to remote exception");
        }
        return false;
    }

    /**
    * Enable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean enableSupplicant() {
        if (!retrieveWificondAndRegisterForDeath()) {
            return false;
        }
        try {
            return mWificond.enableSupplicant();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to enable supplicant due to remote exception");
        }
        return false;
    }

    /**
    * Request signal polling to wificond.
    * Returns an SignalPollResult object.
    * Returns null on failure.
    */
    public WifiNative.SignalPollResult signalPoll() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = mClientInterface.signalPoll();
            if (resultArray == null || resultArray.length != 3) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        WifiNative.SignalPollResult pollResult = new WifiNative.SignalPollResult();
        pollResult.currentRssi = resultArray[0];
        pollResult.txBitrate = resultArray[1];
        pollResult.associationFrequency = resultArray[2];
        return pollResult;
    }

    /**
    * Fetch TX packet counters on current connection from wificond.
    * Returns an TxPacketCounters object.
    * Returns null on failure.
    */
    public WifiNative.TxPacketCounters getTxPacketCounters() {
        if (mClientInterface == null) {
            Log.e(TAG, "No valid wificond client interface handler");
            return null;
        }

        int[] resultArray;
        try {
            resultArray = mClientInterface.getPacketCounters();
            if (resultArray == null || resultArray.length != 2) {
                Log.e(TAG, "Invalid signal poll result from wificond");
                return null;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to do signal polling due to remote exception");
            return null;
        }
        WifiNative.TxPacketCounters counters = new WifiNative.TxPacketCounters();
        counters.txSucceeded = resultArray[0];
        counters.txFailed = resultArray[1];
        return counters;
    }

    /**
    * Fetch the latest scan result from kernel via wificond.
    * @return Returns an ArrayList of ScanDetail.
    * Returns an empty ArrayList on failure.
    */
    public ArrayList<ScanDetail> getScanResults(int scanType) {
        ArrayList<ScanDetail> results = new ArrayList<>();
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return results;
        }
        try {
            NativeScanResult[] nativeResults;
            if (scanType == SCAN_TYPE_SINGLE_SCAN) {
                nativeResults = mWificondScanner.getScanResults();
            } else {
                nativeResults = mWificondScanner.getPnoScanResults();
            }
            for (NativeScanResult result : nativeResults) {
                WifiSsid wifiSsid = WifiSsid.createFromByteArray(result.ssid);
                String bssid;
                try {
                    bssid = NativeUtil.macAddressFromByteArray(result.bssid);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + result.bssid, e);
                    continue;
                }
                if (bssid == null) {
                    Log.e(TAG, "Illegal null bssid");
                    continue;
                }
                ScanResult.InformationElement[] ies =
                        InformationElementUtil.parseInformationElements(result.infoElement);
                InformationElementUtil.Capabilities capabilities =
                        new InformationElementUtil.Capabilities();
                capabilities.from(ies, result.capability);
                String flags = capabilities.generateCapabilitiesString();
                NetworkDetail networkDetail;
                try {
                    networkDetail = new NetworkDetail(bssid, ies, null, result.frequency);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument for scan result with bssid: " + bssid, e);
                    continue;
                }

                ScanDetail scanDetail = new ScanDetail(networkDetail, wifiSsid, bssid, flags,
                        result.signalMbm / 100, result.frequency, result.tsf, ies, null);
                // Update carrier network info if this AP's SSID is associated with a carrier Wi-Fi
                // network and it uses EAP.
                if (ScanResultUtil.isScanResultForEapNetwork(scanDetail.getScanResult())
                        && mCarrierNetworkConfig.isCarrierNetwork(wifiSsid.toString())) {
                    scanDetail.getScanResult().isCarrierAp = true;
                    scanDetail.getScanResult().carrierApEapType =
                            mCarrierNetworkConfig.getNetworkEapType(wifiSsid.toString());
                    scanDetail.getScanResult().carrierName =
                            mCarrierNetworkConfig.getCarrierName(wifiSsid.toString());
                }
                results.add(scanDetail);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to create ScanDetail ArrayList");
        }
        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "get " + results.size() + " scan results from wificond");
        }

        return results;
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(Set<Integer> freqs, Set<String> hiddenNetworkSSIDs) {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        SingleScanSettings settings = new SingleScanSettings();
        settings.channelSettings  = new ArrayList<>();
        settings.hiddenNetworks  = new ArrayList<>();

        if (freqs != null) {
            for (Integer freq : freqs) {
                ChannelSettings channel = new ChannelSettings();
                channel.frequency = freq;
                settings.channelSettings.add(channel);
            }
        }
        if (hiddenNetworkSSIDs != null) {
            for (String ssid : hiddenNetworkSSIDs) {
                HiddenNetwork network = new HiddenNetwork();
                try {
                    network.ssid = NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(ssid));
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + ssid, e);
                    continue;
                }
                settings.hiddenNetworks.add(network);
            }
        }

        try {
            return mWificondScanner.scan(settings);
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request scan due to remote exception");
        }
        return false;
    }

    /**
     * Start PNO scan.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(WifiNative.PnoSettings pnoSettings) {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        PnoSettings settings = new PnoSettings();
        settings.pnoNetworks  = new ArrayList<>();
        settings.intervalMs = pnoSettings.periodInMs;
        settings.min2gRssi = pnoSettings.min24GHzRssi;
        settings.min5gRssi = pnoSettings.min5GHzRssi;
        if (pnoSettings.networkList != null) {
            for (WifiNative.PnoNetwork network : pnoSettings.networkList) {
                PnoNetwork condNetwork = new PnoNetwork();
                condNetwork.isHidden = (network.flags
                        & WifiScanner.PnoSettings.PnoNetwork.FLAG_DIRECTED_SCAN) != 0;
                try {
                    condNetwork.ssid =
                            NativeUtil.byteArrayFromArrayList(NativeUtil.decodeSsid(network.ssid));
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Illegal argument " + network.ssid, e);
                    continue;
                }
                settings.pnoNetworks.add(condNetwork);
            }
        }

        try {
            boolean success = mWificondScanner.startPnoScan(settings);
            mWifiInjector.getWifiMetrics().incrementPnoScanStartAttempCount();
            if (!success) {
                mWifiInjector.getWifiMetrics().incrementPnoScanFailedCount();
            }
            return success;
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to start pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Stop PNO scan.
     * @return true on success.
     */
    public boolean stopPnoScan() {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return false;
        }
        try {
            return mWificondScanner.stopPnoScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to stop pno scan due to remote exception");
        }
        return false;
    }

    /**
     * Abort ongoing single scan.
     */
    public void abortScan() {
        if (mWificondScanner == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return;
        }
        try {
            mWificondScanner.abortScan();
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request abortScan due to remote exception");
        }
    }

    /**
     * Query the list of valid frequencies for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * The following bands are supported:
     * WifiScanner.WIFI_BAND_24_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ
     * WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int [] getChannelsForBand(int band) {
        if (mWificond == null) {
            Log.e(TAG, "No valid wificond scanner interface handler");
            return null;
        }
        try {
            switch (band) {
                case WifiScanner.WIFI_BAND_24_GHZ:
                    return mWificond.getAvailable2gChannels();
                case WifiScanner.WIFI_BAND_5_GHZ:
                    return mWificond.getAvailable5gNonDFSChannels();
                case WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY:
                    return mWificond.getAvailableDFSChannels();
                default:
                    throw new IllegalArgumentException("unsupported band " + band);
            }
        } catch (RemoteException e1) {
            Log.e(TAG, "Failed to request getChannelsForBand due to remote exception");
        }
        return null;
    }

    /**
     * Start Soft AP operation using the provided configuration.
     *
     * @param config Configuration to use for the soft ap created.
     * @param listener Callback for AP events.
     * @return true on success, false otherwise.
     */
    public boolean startSoftAp(WifiConfiguration config, SoftApListener listener) {
        if (mApInterface == null) {
            Log.e(TAG, "No valid ap interface handler");
            return false;
        }
        int encryptionType = getIApInterfaceEncryptionType(config);
        try {
            // TODO(b/67745880) Note that config.SSID is intended to be either a
            // hex string or "double quoted".
            // However, it seems that whatever is handing us these configurations does not obey
            // this convention.
            boolean success = mApInterface.writeHostapdConfig(
                    config.SSID.getBytes(StandardCharsets.UTF_8), config.hiddenSSID,
                    config.apChannel, encryptionType,
                    (config.preSharedKey != null)
                            ? config.preSharedKey.getBytes(StandardCharsets.UTF_8)
                            : new byte[0]);
            if (!success) {
                Log.e(TAG, "Failed to write hostapd configuration");
                return false;
            }
            mApInterfaceListener = new ApInterfaceEventCallback(listener);
            success = mApInterface.startHostapd(mApInterfaceListener);
            if (!success) {
                Log.e(TAG, "Failed to start hostapd.");
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in starting soft AP: " + e);
            return false;
        }
        return true;
    }

    /**
     * Stop the ongoing Soft AP operation.
     *
     * @return true on success, false otherwise.
     */
    public boolean stopSoftAp() {
        if (mApInterface == null) {
            Log.e(TAG, "No valid ap interface handler");
            return false;
        }
        try {
            boolean success = mApInterface.stopHostapd();
            if (!success) {
                Log.e(TAG, "Failed to stop hostapd.");
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Exception in stopping soft AP: " + e);
            return false;
        }
        mApInterfaceListener = null;
        return true;
    }

    private static int getIApInterfaceEncryptionType(WifiConfiguration localConfig) {
        int encryptionType;
        switch (localConfig.getAuthType()) {
            case WifiConfiguration.KeyMgmt.NONE:
                encryptionType = IApInterface.ENCRYPTION_TYPE_NONE;
                break;
            case WifiConfiguration.KeyMgmt.WPA_PSK:
                encryptionType = IApInterface.ENCRYPTION_TYPE_WPA;
                break;
            case WifiConfiguration.KeyMgmt.WPA2_PSK:
                encryptionType = IApInterface.ENCRYPTION_TYPE_WPA2;
                break;
            default:
                // We really shouldn't default to None, but this was how NetworkManagementService
                // used to do this.
                encryptionType = IApInterface.ENCRYPTION_TYPE_NONE;
                break;
        }
        return encryptionType;
    }

    /**
     * Clear all internal handles.
     */
    private void clearState() {
        // Refresh handlers
        mClientInterface = null;
        mWificondScanner = null;
        mPnoScanEventHandler = null;
        mScanEventHandler = null;
        mApInterface = null;
        mApInterfaceListener = null;
    }
}
