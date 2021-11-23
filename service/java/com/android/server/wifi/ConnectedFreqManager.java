/* Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted (subject to the limitations in the
 * disclaimer below) provided that the following conditions are met:
 *
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *   * Redistributions in binary form must reproduce the above
 *     copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided
 *     with the distribution.
 *
 *   * Neither the name of Qualcomm Innovation Center, Inc. nor the names of its
 *     contributors may be used to endorse or promote products derived
 *     from this software without specific prior written permission.
 *
 * NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
 * GRANTED BY THIS LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
 * HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER
 * IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.server.wifi;

import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;
import android.text.TextUtils;

import com.android.wifi.resources.R;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

import com.android.server.wifi.util.WifiConfigStoreEncryptionUtil;
import com.android.server.wifi.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.text.ParseException;

/**
 * Retains information about the connected channels from past few days
 * for networks and persist the channels along with time stamp even after reboot.
 *
 * The purpose is to better inform future network selection for pno scan
 * by this device.
 */
public class ConnectedFreqManager {

    private static final String TAG = "ConnectedFreqManager";
    private boolean mVerboseLoggingEnabled = false;
    private final Context mContext;
    private final Clock mClock;
    private HashMap <String, PerNetwork> mNetworkList;
    private int mMaxChannelPerNetwork = 0;
    /**
     * @param clock is the time source
     * @param context
     */
    public ConnectedFreqManager(Clock clock, Context context) {
        mClock = clock;
        mContext = context;
        mNetworkList = new HashMap<>();
        mMaxChannelPerNetwork = mContext.getResources().getInteger(
                R.integer.config_wifiFrameworkMaxNumofChannelsPerNetwork);
    }

    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    /**
     * A class collecting the freq along with connected time stamp one network or SSID.
     */
    final class PerNetwork {
        public final String configKey;
        private HashMap<String, String> mFreqList;

        PerNetwork(String configKey) {
            this.configKey = configKey;
            mFreqList = new HashMap<>();
        }

        /**
         * Will trim frequency list and evict the least recently added frequency
         * if the cache is full.
         */
        void optimizeNetworkList() {
            if (mFreqList.size() > mMaxChannelPerNetwork) {
                //remove the oldest entry till list is trimmed to maxEntries
                Iterator<Map.Entry<String,String>> iter = mFreqList.entrySet().iterator();
                Long olderTimeStamp = Long.MAX_VALUE;
                String removeEntryKey = null;
                while (iter.hasNext()) {
                    Map.Entry<String,String> entry = iter.next();
                    Date freqTimestampinDate = null;
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                    try {
                        freqTimestampinDate = sdf.parse(entry.getValue());
                    } catch (ParseException e) {
                        Log.e(TAG, "Error in Date Parsing");
                        continue;
                    }
                    if (freqTimestampinDate.getTime() < olderTimeStamp) {
                        olderTimeStamp = freqTimestampinDate.getTime();
                        removeEntryKey = entry.getKey();
                    }
                }
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Remove frequency " + removeEntryKey + " and corresponding older "
                            + "time stamp " + olderTimeStamp);
                }
                mFreqList.remove(removeEntryKey);
            }
        }

        /**
         * Add a frequency to the list of frequencies for this network.
         * Will evict the least recently added frequency if the cache is full.
         */
        void addFrequency(int frequency) {
            DateTimeFormatter LONG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            mFreqList.put(String.valueOf(frequency), (LONG_FORMATTER.format(LocalDateTime.now())));
        }

        /**
         * Retrieve the list of frequencies seen for this network.
         * @param ageInMills Max age to filter the channels.
         * @return a list of frequencies
         */
        List<Integer> getAllFrequency(Long ageInMills) {
            List<Integer> results = new ArrayList<>();
            DateTimeFormatter LONG_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            String currentDateInString = LONG_FORMATTER.format(LocalDateTime.now());
            Date currentTimestampinDate = null;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            try {
                currentTimestampinDate = sdf.parse(currentDateInString);
            } catch (ParseException e) {
                Log.e(TAG, "Error in current Date Parsing");
                return null;
            }
            Long nowInMills = currentTimestampinDate.getTime();
            if (mFreqList == null) return null;
            Iterator<Map.Entry<String,String>> iter = mFreqList.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String,String> entry = iter.next();
                Date freqTimestampinDate = null;
                try {
                    freqTimestampinDate = sdf.parse(entry.getValue());
                } catch (ParseException e) {
                    Log.e(TAG, "Error in Date Parsing from frequency list");
                    continue;
                }
                if ((nowInMills - freqTimestampinDate.getTime()) > ageInMills) {
                    if (mVerboseLoggingEnabled) {
                        Log.d(TAG, "Remove frequency " + entry.getKey() + " and corresponding older "
                                + "time stamp " + freqTimestampinDate);
                    }
                    iter.remove();
                    continue;
                }
                results.add(Integer.parseInt(entry.getKey()));
            }
            return results;
        }

        HashMap<String, String> getFrequencyListMap() {
            return mFreqList;
        }

        void addAll(HashMap<String, String> freqList) {
            if (freqList == null) return;
            for (Map.Entry<String, String> entry : freqList.entrySet()) {
                mFreqList.put(entry.getKey(), entry.getValue());
                optimizeNetworkList();
            }
        }
    }

    PerNetwork lookupNetwork(String configKey) {
        if (configKey == null || TextUtils.isEmpty(configKey)) {
            return new PerNetwork("");
        }
        if (mNetworkList == null) return null;
        PerNetwork ans = mNetworkList.get(configKey);
        if (ans == null) {
            ans = new PerNetwork(configKey);
            mNetworkList.put(configKey, ans);
        }
        return ans;
    }
    void addFrequency(String configKey, int freq) {
        PerNetwork network = lookupNetwork(configKey);
        if (network == null) return;
        network.addFrequency(freq);
        network.optimizeNetworkList();
    }

    List<Integer> getConnectedFreqList(String configKey, Long ageInMillis) {
        PerNetwork network = lookupNetwork(configKey);
        if (network == null) return null;
        return network.getAllFrequency(ageInMillis);
    }

    HashMap<String, String> getFrequencyListMap(String configKey) {
        PerNetwork network = lookupNetwork(configKey);
        if (network == null) return null;
        return network.getFrequencyListMap();
    }
    /**
     * Remove network from cache
     * @param configKey
     */
    public void removeNetwork(String configKey) {
        if (configKey != null && !TextUtils.isEmpty(configKey))
            mNetworkList.remove(configKey);
    }

    public void addAll(HashMap<String, HashMap<String, String>> list) {
        if(list == null) return;
        for (Map.Entry<String, HashMap<String, String>> entry : list.entrySet()) {
            PerNetwork network = new PerNetwork(entry.getKey());
            network.addAll(entry.getValue());
            mNetworkList.put(entry.getKey(), network);
        }
    }

    /**
     * This class performs serialization and parsing of XML data block that contain the mapping
     * from configKey to connected frequency map
     * (XML block data inside <ConnectedFreqListMap> tag).
     */
    public class ConnectedFreqStoreData implements WifiConfigStore.StoreData {
        private static final String TAG = "ConnectedFreqStoreData";
        private static final String XML_TAG_SECTION_HEADER_FREQ_LIST = "ConnectedFreqListMap";
        private static final String XML_TAG_FREQ_LIST = "FreqListEntry";

        private HashMap<String, HashMap<String, String>> mFreqList;

        ConnectedFreqStoreData() {}

        @Override
        public void serializeData(XmlSerializer out,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            if (mFreqList != null) {
                XmlUtil.writeNextValue(out, XML_TAG_FREQ_LIST, mFreqList);
            }
        }

        @Override
        public void deserializeData(XmlPullParser in, int outerTagDepth,
                @WifiConfigStore.Version int version,
                @Nullable WifiConfigStoreEncryptionUtil encryptionUtil)
                throws XmlPullParserException, IOException {
            // Ignore empty reads.
            if (in == null) {
                return;
            }
            while (!XmlUtil.isNextSectionEnd(in, outerTagDepth)) {
                String[] valueName = new String[1];
                Object value = XmlUtil.readCurrentValue(in, valueName);
                if (valueName[0] == null) {
                    throw new XmlPullParserException("Missing value name");
                }
                switch (valueName[0]) {
                    case XML_TAG_FREQ_LIST:
                        mFreqList = (HashMap<String, HashMap<String, String>>) value;
                        break;
                    default:
                        Log.w(TAG, "Ignoring unknown tag under "
                                + XML_TAG_SECTION_HEADER_FREQ_LIST
                                + ": " + valueName[0]);
                        break;
                }
            }
        }

        @Override
        public void resetData() {
            mFreqList = null;
        }

        @Override
        public boolean hasNewDataToSerialize() {
            // always persist.
            return true;
        }

        @Override
        public String getName() {
            return XML_TAG_SECTION_HEADER_FREQ_LIST;
        }

        @Override
        public @WifiConfigStore.StoreFileId int getStoreFileId() {
            // Shared general store.
            return WifiConfigStore.STORE_FILE_SHARED_GENERAL;
        }

        /**
         * An empty Map will be returned for null frequency, timestamp map.
         *
         * @return Map of mapping from configKey to map of frequency and connected time stamp.
         */
        public HashMap<String, HashMap<String, String>> getFreqList() {
            if (mFreqList == null) {
                mNetworkList = null;
                return new HashMap<String, HashMap<String, String>>();
            }
            return mFreqList;
        }

        /**
         * Sets the data to be stored to file.
         * @param freqList
         */
        public void setFreqList(HashMap<String, HashMap<String, String>> freqList) {
            mFreqList = freqList;
        }
    }
}

