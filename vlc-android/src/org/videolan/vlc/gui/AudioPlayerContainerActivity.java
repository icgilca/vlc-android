/*
 * *************************************************************************
 *  SlidingPaneActivity.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.AppBarLayout;
import android.support.design.widget.BottomSheetBehavior;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.support.v7.widget.Toolbar;
import android.support.v7.widget.ViewStubCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.medialibrary.Medialibrary;
import org.videolan.vlc.BuildConfig;
import org.videolan.vlc.PlaybackService;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.gui.audio.AudioPlayer;
import org.videolan.vlc.interfaces.IRefreshable;
import org.videolan.vlc.media.MediaUtils;
import org.videolan.vlc.util.Strings;
import org.videolan.vlc.util.WeakHandler;

public class AudioPlayerContainerActivity extends AppCompatActivity implements PlaybackService.Client.Callback, PlaybackService.Callback {

    public static final String TAG = "VLC/AudioPlayerContainerActivity";
    public static final String ACTION_SHOW_PLAYER = Strings.buildPkgString("gui.ShowPlayer");

    static {
        AppCompatDelegate.setDefaultNightMode(PreferenceManager.getDefaultSharedPreferences(VLCApplication.getAppContext()).getBoolean("daynight", false) ? AppCompatDelegate.MODE_NIGHT_AUTO : AppCompatDelegate.MODE_NIGHT_NO);
    }

    protected static final String ID_VIDEO = "video";
    protected static final String ID_AUDIO = "audio";
    protected static final String ID_NETWORK = "network";
    protected static final String ID_DIRECTORIES = "directories";
    protected static final String ID_HISTORY = "history";
    protected static final String ID_MRL = "mrl";
    protected static final String ID_PREFERENCES = "preferences";
    protected static final String ID_ABOUT = "about";

    protected AppBarLayout mAppBarLayout;
    protected Toolbar mToolbar;
    protected AudioPlayer mAudioPlayer;
    private FrameLayout mAudioPlayerContainer;
    protected SharedPreferences mSettings;
    private final PlaybackServiceActivity.Helper mHelper = new PlaybackServiceActivity.Helper(this, this);
    protected PlaybackService mService;
    protected BottomSheetBehavior mBottomSheetBehavior;
    private FrameLayout mFragmentContainer;

    protected boolean mPreventRescan = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        /* Get settings */
        mSettings = PreferenceManager.getDefaultSharedPreferences(this);
        /* Theme must be applied before super.onCreate */
        applyTheme();

        MediaUtils.updateSubsDownloaderActivity(this);

        super.onCreate(savedInstanceState);
    }

    protected void initAudioPlayerContainerActivity() {

        mToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(mToolbar);
        mAppBarLayout = (AppBarLayout) findViewById(R.id.appbar);
        mAppBarLayout.setExpanded(true);

        mAudioPlayerContainer = (FrameLayout) findViewById(R.id.audio_player_container);
    }

    private void initAudioPlayer() {
        ((ViewStubCompat)findViewById(R.id.audio_player_stub)).inflate();
        mAudioPlayer = (AudioPlayer) getSupportFragmentManager().findFragmentById(R.id.audio_player);
        mAudioPlayer.setUserVisibleHint(false);
        mBottomSheetBehavior = BottomSheetBehavior.from(mAudioPlayerContainer);
        mBottomSheetBehavior.setPeekHeight(getResources().getDimensionPixelSize(R.dimen.player_peek_height));
        mBottomSheetBehavior.setBottomSheetCallback(mAudioPlayerBottomSheetCallback);
        mAudioPlayer.showAudioPlayerTips();
    }

    @Override
    protected void onPostCreate(@Nullable Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mFragmentContainer = (FrameLayout) findViewById(R.id.fragment_placeholder);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mHelper.onStart();

        //Handle external storage state
        IntentFilter storageFilter = new IntentFilter();
        storageFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        storageFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        storageFilter.addDataScheme("file");
        registerReceiver(storageReceiver, storageFilter);

        /* Prepare the progressBar */
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SHOW_PLAYER);
        registerReceiver(messageReceiver, filter);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        mPreventRescan = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBottomSheetBehavior != null && mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_COLLAPSED)
            mFragmentContainer.setPadding(0, 0, 0, mBottomSheetBehavior.getPeekHeight());
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(storageReceiver);
        unregisterReceiver(messageReceiver);
        mHelper.onStop();
    }

    private void applyTheme() {
        boolean enableBlackTheme = mSettings.getBoolean("enable_black_theme", false);
        if (VLCApplication.showTvUi() || enableBlackTheme) {
            setTheme(R.style.Theme_VLC_Black);
        }
    }

    public void updateLib() {
        if (mPreventRescan) {
            mPreventRescan = false;
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        Fragment current = fm.findFragmentById(R.id.fragment_placeholder);
        if (current != null && current instanceof IRefreshable)
            ((IRefreshable) current).refresh();
    }

    /**
     * Show a tip view.
     * @param stubId the stub of the tip view
     * @param settingKey the setting key to check if the view must be displayed or not.
     */
    public void showTipViewIfNeeded(final int stubId, final String settingKey) {
        if (BuildConfig.DEBUG)
            return;
        ViewStubCompat vsc = (ViewStubCompat) findViewById(stubId);
        if (vsc != null && !mSettings.getBoolean(settingKey, false) && !VLCApplication.showTvUi()) {
            View v = vsc.inflate();
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeTipViewIfDisplayed();
                }
            });
            TextView okGotIt = (TextView) v.findViewById(R.id.okgotit_button);
            okGotIt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    removeTipViewIfDisplayed();
                    SharedPreferences.Editor editor = mSettings.edit();
                    editor.putBoolean(settingKey, true);
                    editor.apply();
                }
            });
        }
    }

    /**
     * Remove the current tip view if there is one displayed.
     */
    public void removeTipViewIfDisplayed() {
        View tips = findViewById(R.id.audio_tips);
        if (tips == null)
            return;
        ViewGroup root = (ViewGroup) tips.getParent();
        for (int i = 0; i < root.getChildCount(); ++i){
            if (root.getChildAt(i).getId() == R.id.audio_tips)
                root.removeViewAt(i);
        }
    }
    /**
     * Show the audio player.
     */
    public synchronized void showAudioPlayer() {
        if (!isAudioPlayerReady())
            initAudioPlayer();
        if (mAudioPlayerContainer.getVisibility() == View.GONE) {
            mAudioPlayerContainer.setVisibility(View.VISIBLE);
            mFragmentContainer.setPadding(0, 0, 0, mBottomSheetBehavior.getPeekHeight());
        }
        if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
        }
    }

    /**
     * Slide down the audio player.
     * @return true on success else false.
     */
    public boolean slideDownAudioPlayer() {
        if (!isAudioPlayerReady())
            return false;
        if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED) {
            mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
            return true;
        }
        return false;
    }

    /**
     * Slide up and down the audio player depending on its current state.
     */
    public void slideUpOrDownAudioPlayer() {
        if (!isAudioPlayerReady())
            return;
        if (mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_HIDDEN)
            return;
        mBottomSheetBehavior.setState(mBottomSheetBehavior.getState() == BottomSheetBehavior.STATE_EXPANDED?
                BottomSheetBehavior.STATE_COLLAPSED : BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * Hide the audio player.
     */
    public void hideAudioPlayer() {
        if (!isAudioPlayerReady())
            return;
        mBottomSheetBehavior.setHideable(true);
        mBottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
    }

    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equalsIgnoreCase(ACTION_SHOW_PLAYER))
                showAudioPlayer();
        }
    };

    private final BroadcastReceiver storageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equalsIgnoreCase(Intent.ACTION_MEDIA_MOUNTED))
                mActivityHandler.obtainMessage(ACTION_MEDIA_MOUNTED, intent.getData()).sendToTarget();
            else if (action.equalsIgnoreCase(Intent.ACTION_MEDIA_UNMOUNTED))
                mActivityHandler.sendMessageDelayed(mActivityHandler.obtainMessage(ACTION_MEDIA_UNMOUNTED, intent.getData()), 100);
        }
    };

    Handler mActivityHandler = new StorageHandler(this);
    AudioPlayerBottomSheetCallback mAudioPlayerBottomSheetCallback = new AudioPlayerBottomSheetCallback();

    private static final int ACTION_MEDIA_MOUNTED = 1337;
    private static final int ACTION_MEDIA_UNMOUNTED = 1338;

    @Override
    public void update() {}

    @Override
    public void updateProgress() {
        if (!isAudioPlayerReady())
            showAudioPlayer();
    }

    @Override
    public void onMediaEvent(Media.Event event) {}

    @Override
    public void onMediaPlayerEvent(MediaPlayer.Event event) {}

    public boolean isAudioPlayerReady() {
        return mAudioPlayer != null;
    }

    private class AudioPlayerBottomSheetCallback extends BottomSheetBehavior.BottomSheetCallback {
        @Override
        public void onStateChanged(@NonNull View bottomSheet, int newState) {
            mAudioPlayer.onStateChanged(newState);
            switch (newState) {
                case BottomSheetBehavior.STATE_COLLAPSED:
                    mBottomSheetBehavior.setHideable(false);
                    removeTipViewIfDisplayed();
                    mFragmentContainer.setPadding(0, 0, 0, mBottomSheetBehavior.getPeekHeight());
                    break;
                case BottomSheetBehavior.STATE_EXPANDED:
                    mBottomSheetBehavior.setHideable(false);
                    break;
                case BottomSheetBehavior.STATE_HIDDEN:
                    removeTipViewIfDisplayed();
                    mFragmentContainer.setPadding(0, 0, 0, 0);
                    break;
            }
        }

        @Override
        public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
    }

    private static class StorageHandler extends WeakHandler<AudioPlayerContainerActivity> {

        StorageHandler(AudioPlayerContainerActivity owner) {
            super(owner);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Medialibrary ml = VLCApplication.getMLInstance();
            switch (msg.what){
                case ACTION_MEDIA_MOUNTED:
                    String path = ((Uri) msg.obj).getPath();
                    removeMessages(ACTION_MEDIA_UNMOUNTED);
                    ml.addDevice(path, path, true);
                    ml.discover(path);
                    getOwner().updateLib();
                    ml.reload();
                    break;
                case ACTION_MEDIA_UNMOUNTED:
                    ml.removeDevice(((Uri) msg.obj).getPath());
                    ml.reload();
                    getOwner().updateLib();
                    break;
            }
        }
    }

    public PlaybackServiceActivity.Helper getHelper() {
        return mHelper;
    }

    @Override
    public void onConnected(PlaybackService service) {
        service.addCallback(this);
        mService = service;
    }

    @Override
    public void onDisconnected() {
        if (mService != null)
            mService.removeCallback(this);
        mService = null;
    }
}
