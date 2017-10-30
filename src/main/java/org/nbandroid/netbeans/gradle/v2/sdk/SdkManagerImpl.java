/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.nbandroid.netbeans.gradle.v2.sdk;

import com.android.repository.api.Channel;
import com.android.repository.api.ProgressRunner;
import com.android.repository.api.RepoManager;
import com.android.repository.api.RepoManager.RepoLoadedCallback;
import com.android.repository.api.RepoPackage;
import com.android.repository.api.SettingsController;
import com.android.repository.api.UpdatablePackage;
import com.android.repository.impl.meta.RepositoryPackages;
import com.android.repository.impl.meta.TypeDetails;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.repository.AndroidSdkHandler;
import com.android.sdklib.repository.meta.DetailsTypes;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.gradle.impldep.com.google.common.collect.ImmutableList;
import org.netbeans.modules.android.core.sdk.DalvikPlatformManager;
import org.netbeans.modules.android.core.sdk.NbDownloader;
import org.netbeans.modules.android.core.sdk.NbOutputWindowProgressIndicator;
import org.openide.util.Exceptions;
import org.openide.util.lookup.ServiceProvider;
import org.openide.windows.WindowManager;

/**
 * Default SDK Manager implementation
 *
 * @author arsi
 */
@ServiceProvider(service = SdkManager.class)
public class SdkManagerImpl extends SdkManager implements RepoLoadedCallback {

    private RepoManager repoManager;
    private static final ExecutorService pool = Executors.newFixedThreadPool(1);
    private final Vector<SdkPlatformChangeListener> listeners = new Vector<>();
    private SdkPlatformPackagesRootNode platformPackages = null;

    /**
     * Get Android repo manager
     *
     * @return RepoManager or null if no SDK location is set
     */
    @Override
    public RepoManager getRepoManager() {
        return repoManager;
    }

    /**
     * Add SdkPlatformChangeListener to listen of SDK platform pakages list
     * changes. On add is listener called with actual package list. Listener is
     * called from actual thread
     *
     * @param l SdkPlatformChangeListener
     */
    @Override
    public void addSdkPlatformChangeListener(SdkPlatformChangeListener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
            if (platformPackages != null) {
                try {
                    l.packageListChanged(platformPackages);
                } catch (Exception e) {
                    Exceptions.printStackTrace(e);
                }
            }
        }
    }

    /**
     * Remove SdkPlatformChangeListener
     *
     * @param l SdkPlatformChangeListener
     */
    @Override
    public void removeSdkPlatformChangeListener(SdkPlatformChangeListener l) {
        listeners.remove(l);
    }

    public SdkManagerImpl() {
        //TODO listen to sdk dir change
        final Runnable runnable = new Runnable() {
            public void run() {
                AndroidSdkHandler sdkManager = DalvikPlatformManager.getDefault().getSdkManager();
                if (sdkManager != null) {
                    repoManager = sdkManager.getSdkManager(new NbOutputWindowProgressIndicator());
                    repoManager.registerLocalChangeListener(SdkManagerImpl.this);
                    repoManager.registerRemoteChangeListener(SdkManagerImpl.this);
                    updateSdkPlatformPackages();
                } else {
                    repoManager = null;
                }
            }
        };
        WindowManager.getDefault().invokeWhenUIReady(new Runnable() {
            @Override
            public void run() {
                pool.submit(runnable);
            }
        });
    }

    /**
     * Update SDK platform pakages list After update is called
     * SdkPlatformChangeListener
     */
    @Override
    public void updateSdkPlatformPackages() {
        if (repoManager != null) {
            repoManager.load(5000, ImmutableList.of(),
                    ImmutableList.of(),
                    ImmutableList.of(new DownloadErrorCallback()),
                    new NbProgressRunner(), new NbDownloader(), new NbSettingsController(), false);
        }
    }

    @Override
    public void doRun(RepositoryPackages packages) {
        loadPackages(packages);
    }

    private void loadPackages(RepositoryPackages packages) {
        List<AndroidVersionNode> tmpPackages = new ArrayList<>();
        Map<AndroidVersion, AndroidVersionNode> tmp = new HashMap<>();
        Set<UpdatablePackage> toolsPackages = Sets.newTreeSet();
        for (UpdatablePackage info : packages.getConsolidatedPkgs().values()) {
            RepoPackage p = info.getRepresentative();
            TypeDetails details = p.getTypeDetails();
            if (details instanceof DetailsTypes.ApiDetailsType) {
                AndroidVersion androidVersion = ((DetailsTypes.ApiDetailsType) details).getAndroidVersion();
                if (tmp.containsKey(androidVersion)) {
                    AndroidVersionNode avd = tmp.get(androidVersion);
                    avd.addPackage(new SdkPackageNode(avd, info));
                } else {
                    AndroidVersionNode avd = new AndroidVersionNode(androidVersion);
                    avd.addPackage(new SdkPackageNode(avd, info));
                    tmp.put(androidVersion, avd);
                    tmpPackages.add(avd);
                }
            } else {
                toolsPackages.add(info);
            }
        }
        Collections.sort(tmpPackages, new Comparator<AndroidVersionNode>() {
            @Override
            public int compare(AndroidVersionNode o1, AndroidVersionNode o2) {
                return o2.getCodeName().compareTo(o1.getCodeName());
            }
        });
        platformPackages = new SdkPlatformPackagesRootNode(tmpPackages);
        for (SdkPlatformChangeListener listener : listeners) {
            try {
                listener.packageListChanged(platformPackages);
            } catch (Exception e) {
                Exceptions.printStackTrace(e);
            }
        }
    }

    private class DownloadErrorCallback implements Runnable {

        @Override
        public void run() {
        }

    }

    private class NbProgressRunner implements ProgressRunner {

        @Override
        public void runAsyncWithProgress(ProgressRunner.ProgressRunnable r) {
            r.run(new NbOutputWindowProgressIndicator(), this);
        }

        @Override
        public void runSyncWithProgress(ProgressRunner.ProgressRunnable r) {
            r.run(new NbOutputWindowProgressIndicator(), this);
        }

        @Override
        public void runSyncWithoutProgress(Runnable r) {
            pool.submit(r);
        }

    }

    private class NbSettingsController implements SettingsController {

        boolean force = false;

        @Override
        public boolean getForceHttp() {
            return force;
        }

        @Override
        public void setForceHttp(boolean force) {
            this.force = force;
        }

        @Override
        public Channel getChannel() {
            //TODO add channel selection to Android settings
            return Channel.create(0);
        }

    }

}
