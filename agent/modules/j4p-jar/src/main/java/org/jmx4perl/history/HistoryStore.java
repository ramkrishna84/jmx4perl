package org.jmx4perl.history;

import org.json.simple.JSONObject;

import org.jmx4perl.JmxRequest;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import static org.jmx4perl.JmxRequest.Type.*;

import java.util.*;
import java.io.Serializable;

/*
 * jmx4perl - WAR Agent for exporting JMX via JSON
 *
 * Copyright (C) 2009 Roland Huß, roland@cpan.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 *
 * A commercial license is available as well. Please contact roland@cpan.org for
 * further details.
 */

/**
 * Store for remembering old values.
 *
 * @author roland
 * @since Jun 12, 2009
 */
public class HistoryStore implements Serializable {

    private static final long serialVersionUID = 42L;

    // Hard limit for number of entries for a single history track
    private int globalMaxEntries;

    private Map<HistoryKey, HistoryEntry> historyStore;
    private Map<HistoryKey, Integer /* max entries */> patterns;

    public HistoryStore(int pTotalMaxEntries) {
        globalMaxEntries = pTotalMaxEntries;
        historyStore = new HashMap<HistoryKey, HistoryEntry>();
        patterns = new HashMap<HistoryKey, Integer>();
    }

    public int getGlobalMaxEntries() {
        return globalMaxEntries;
    }

    synchronized public void setGlobalMaxEntries(int pGlobalMaxEntries) {
        globalMaxEntries = pGlobalMaxEntries;
        // Refresh all entries
        for (HistoryEntry entry : historyStore.values()) {
            entry.setMaxEntries(globalMaxEntries);
            entry.trim();
        }
    }

    /**
     * Configure the history length for a specific entry. If the length
     * is 0 disable history for this key
     *
     * @param pKey history key
     * @param pMaxEntries number of maximal entries. If larger than globalMaxEntries,
     * then globalMaxEntries is used instead.
     */
    public void configure(HistoryKey pKey,int pMaxEntries) {
        int maxEntries = pMaxEntries > globalMaxEntries ? globalMaxEntries : pMaxEntries;

        // Remove entries if set to 0
        if (pMaxEntries == 0) {
            removeEntries(pKey);
            return;
        }
        if (pKey.isMBeanPattern()) {
            patterns.put(pKey,maxEntries);
            // Trim all already stored keys
            for (HistoryKey key : historyStore.keySet()) {
                if (pKey.matches(key)) {
                    HistoryEntry entry = historyStore.get(key);
                    entry.setMaxEntries(maxEntries);
                    entry.trim();
                }
            }
        } else {
            HistoryEntry entry = historyStore.get(pKey);
            if (entry != null) {
                entry.setMaxEntries(maxEntries);
                entry.trim();
            } else {
                entry = new HistoryEntry(maxEntries);
                historyStore.put(pKey,entry);
            }
        }
    }

    // Remove entries
    private void removeEntries(HistoryKey pKey) {
        if (pKey.isMBeanPattern()) {
            patterns.remove(pKey);
            List<HistoryKey> toRemove = new ArrayList<HistoryKey>();
            for (HistoryKey key : historyStore.keySet()) {
                if (pKey.matches(key)) {
                    toRemove.add(key);
                }
            }
            // Avoid concurrent modification exceptions
            for (HistoryKey key : toRemove) {
                historyStore.remove(key);
            }
        } else {
            HistoryEntry entry = historyStore.get(pKey);
            if (entry != null) {
                historyStore.remove(pKey);
            }
        }
    }

    /**
     * Reset the complete store
     */
    public synchronized void reset() {
        historyStore = new HashMap<HistoryKey, HistoryEntry>();
        patterns = new HashMap<HistoryKey, Integer>();
    }

    public synchronized void updateAndAdd(JmxRequest pJmxReq, JSONObject pJson) {
        long timestamp = System.currentTimeMillis() / 1000;
        pJson.put("timestamp",timestamp);

        JmxRequest.Type type  = pJmxReq.getType();
        if (type == EXEC || type == WRITE) {
            HistoryEntry entry = historyStore.get(new HistoryKey(pJmxReq));
            if (entry != null) {
                synchronized(entry) {
                    // A history data to json object for the response
                    pJson.put("history",entry.jsonifyValues());

                    // Update history for next time
                    if (type == EXEC) {
                        entry.add(pJson.get("value"),timestamp);
                    } else if (type == WRITE) {
                        // The new value to set as string representation
                        entry.add(pJmxReq.getValue(),timestamp);
                    }
                }
            }
        } else if (type == READ) {
            updateReadHistory(pJmxReq, pJson, timestamp);
        }
    }

    // Update potentially multiple history entries for a READ request which could
    // return multiple values with a single request
    private void updateReadHistory(JmxRequest pJmxReq, JSONObject pJson, long pTimestamp)  {
        ObjectName name = pJmxReq.getObjectName();
        List<String> attributeNames = pJmxReq.getAttributeNames();
        if (name.isPattern()) {
            // We have a pattern and hence a value structure
            // of bean -> attribute_key -> attribute_value
            JSONObject history = new JSONObject();
            for (Map.Entry<String,Object> beanEntry : ((Map<String,Object>) pJson.get("value")).entrySet()) {
                String beanName = beanEntry.getKey();
                JSONObject beanHistory =
                        addAttributesFromComplexValue(
                                pJmxReq,
                                ((Map<String,Object>) beanEntry.getValue()),
                                beanName,
                                pTimestamp);
                if (beanHistory.size() > 0) {
                    history.put(beanName,beanHistory);
                }
            }
            if (history.size() > 0) {
                pJson.put("history",history);
            }
        } else if (attributeNames != null && attributeNames.size() > 1) {
            // Multiple attributes, but a single bean.
            // Value has the following structure:
            // attribute_key -> attribute_value
            JSONObject history = addAttributesFromComplexValue(
                    pJmxReq,
                    ((Map<String,Object>) pJson.get("value")),
                    pJmxReq.getObjectNameAsString(),
                    pTimestamp);
            if (history.size() > 0) {
                pJson.put("history",history);
            }
        } else if (pJmxReq.getAttributeName() != null) {
            // Single attribute, single bean. Value is the attribute_value
            // itself.
            addAttributeFromSingleValue(pJson,
                                        "history",
                                        new HistoryKey(pJmxReq),
                                        pJson.get("value"),
                                        pTimestamp);
        }
    }

    private JSONObject addAttributesFromComplexValue(JmxRequest pJmxReq,Map<String,Object> pAttributesMap,
                                                     String pBeanName,long pTimestamp) {
        JSONObject ret = new JSONObject();
        for (Map.Entry<String,Object> attrEntry : pAttributesMap.entrySet()) {
            String attrName = attrEntry.getKey();
            Object value = attrEntry.getValue();
            HistoryKey key;
            try {
                key = new HistoryKey(pBeanName,attrName,null /* No path support for complex read handling */,
                                     pJmxReq.getTargetConfigUrl());
            } catch (MalformedObjectNameException e) {
                // Shouldnt occur since we get the MBeanName from a JMX operation's result. However,
                // we will rethrow it
                throw new IllegalArgumentException("Cannot pars MBean name " + pBeanName,e);
            }
            addAttributeFromSingleValue(ret,
                                        attrName,
                                        key,
                                        value,
                                        pTimestamp);
        }
        return ret;
    }

    private void addAttributeFromSingleValue(JSONObject pHistMap, String pAttrName, HistoryKey pKey,
                                             Object pValue, long pTimestamp) {
        HistoryEntry entry = getEntry(pKey,pValue,pTimestamp);
        if (entry != null) {
            synchronized (entry) {
                pHistMap.put(pAttrName,entry.jsonifyValues());
                entry.add(pValue,pTimestamp);
            }
        }
    }

    private HistoryEntry getEntry(HistoryKey pKey,Object pValue,long pTimestamp) {
        HistoryEntry entry = historyStore.get(pKey);
        if (entry != null) {
            return entry;
        }
        // Now try all known patterns and add lazily the key
        for (HistoryKey key : patterns.keySet()) {
            if (key.matches(pKey)) {
                entry = new HistoryEntry(patterns.get(key));
                entry.add(pValue,pTimestamp);
                historyStore.put(pKey,entry);
                return entry;
            }
        }
        return null;
    }

}
