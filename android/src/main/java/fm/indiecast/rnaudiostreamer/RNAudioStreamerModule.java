package fm.indiecast.rnaudiostreamer;

import android.os.Handler;
import android.util.Log;
import android.os.Build;
import android.net.Uri;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;

import java.io.IOException;
import java.lang.Exception;
import java.io.File;
import java.util.Map;
import java.util.List;

import com.danikula.videocache.HttpProxyCacheServer;

public class RNAudioStreamerModule extends ReactContextBaseJavaModule implements ExoPlayer.EventListener, ExtractorMediaSource.EventListener{

    private static final String TAG = "RNAS/Module";

    // Player
    private SimpleExoPlayer player = null;
    private String status = "STOPPED";
    private ReactApplicationContext reactContext = null;

    // AudioFocus
    private boolean mHasAudioFocus = false;

    // Media Cache Proxy
    HttpProxyCacheServer proxy;

    // Media Proxy Cache
    private Integer mMaxCacheFilesCount = 50; // Default 50 files
    private Integer mMaxCacheSize = 1024 * 1024 * 1024; // Default 1 GB

    public RNAudioStreamerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    // Status
    private static final String PLAYING = "PLAYING";
    private static final String PAUSED = "PAUSED";
    private static final String STOPPED = "STOPPED";
    private static final String FINISHED = "FINISHED";
    private static final String BUFFERING = "BUFFERING";
    private static final String ERROR = "ERROR";

    // Module Name
    @Override public String getName() {
        return "RNAudioStreamer";
    }

    // Set the number of files to be stored in cache
    @ReactMethod public void setCacheFileLimit(Integer limit) {
        this.mMaxCacheFilesCount = limit;
    }

    // Set the total cache size (in bytes)
    @ReactMethod public void setCacheSize(Integer size) {
        this.mMaxCacheSize = size;
    }

    // Clear the cache
    @ReactMethod public void clearCache() {
      try{
          Utils.cleanDirectory(reactContext.getExternalCacheDir());
      }catch(IOException e){
          e.printStackTrace();
      }
    }

    // Return cache size as human readable string
    @ReactMethod public void cacheSize(Callback callback) {
        try {
            File cache = new File(reactContext.getExternalCacheDir().toString()+"/video-cache");
            long sizeInBytes = Utils.getFolderSize(cache);

            String sizeInHR = Utils.humanReadableByteCount(sizeInBytes, true);
            callback.invoke(null, sizeInHR);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Check is file cached
    /**
    * Disabled - not fully implemented
    *
    @ReactMethod public boolean isFileCached(String url) {
        return proxy.isCached(url);
    }
    */

    @ReactMethod public void setUrl(String urlString) {

        if (player != null){
            player.stop();
            changeAudioFocus(false);
            player = null;
            status = "STOPPED";
            this.sendStatusEvent();
        }

        // Create Proxy Cache
        proxy = ProxyFactory.getProxy(reactContext, mMaxCacheFilesCount, mMaxCacheSize);
        String proxyUrl = proxy.getProxyUrl(urlString);

        // Create player
        Handler mainHandler = new Handler();
        TrackSelector trackSelector = new DefaultTrackSelector(mainHandler);
        LoadControl loadControl = new DefaultLoadControl();
        this.player = ExoPlayerFactory.newSimpleInstance(reactContext, trackSelector, loadControl);

        // Create source
        ExtractorsFactory extractorsFactory = new DefaultExtractorsFactory();
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(reactContext, getDefaultUserAgent(), bandwidthMeter);
        MediaSource audioSource = new ExtractorMediaSource(Uri.parse(proxyUrl), dataSourceFactory, extractorsFactory, mainHandler, this);

        // Start preparing audio
        player.prepare(audioSource);
        player.addListener(this);
        changeAudioFocus(true);
    }

    @ReactMethod public void play() {
        if(player != null) player.setPlayWhenReady(true);
    }

    @ReactMethod public void pause() {
        if(player != null) player.setPlayWhenReady(false);
    }

    @ReactMethod public void seekToTime(double time) {
        if(player != null) player.seekTo((long)time * 1000);
    }

    @ReactMethod public void currentTime(Callback callback) {
        if (player == null){
            callback.invoke(null,(double)0);
        }else{
            callback.invoke(null,(double)(player.getCurrentPosition()/1000));
        }
    }

    @ReactMethod public void status(Callback callback) {
        callback.invoke(null,status);
    }

    @ReactMethod public void duration(Callback callback) {
        if (player == null){
            callback.invoke(null,(double)0);
        }else{
            callback.invoke(null,(double)(player.getDuration()/1000));
        }
    }

    public boolean isPlaying() {
        if (status.equals("PLAYING")) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        Log.d("onPlayerStateChanged", ""+playbackState);

        switch (playbackState) {
            case ExoPlayer.STATE_IDLE:
                status = STOPPED;
                changeAudioFocus(false);
                this.sendStatusEvent();
                break;
            case ExoPlayer.STATE_BUFFERING:
                status = BUFFERING;
                this.sendStatusEvent();
                break;
            case ExoPlayer.STATE_READY:
                if (this.player != null && this.player.getPlayWhenReady()) {
                    status = PLAYING;
                    this.sendStatusEvent();
                    changeAudioFocus(true);
                } else {
                    status = PAUSED;
                    this.sendStatusEvent();
                }
                break;
            case ExoPlayer.STATE_ENDED:
                status = FINISHED;
                this.sendStatusEvent();
                changeAudioFocus(false);
                break;
        }
    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        status = ERROR;
        this.sendStatusEvent();
    }

    @Override
    public void onPositionDiscontinuity() {

    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        if (isLoading == true){
            status = BUFFERING;
            this.sendStatusEvent();
        }else if (this.player != null){
            if (this.player.getPlayWhenReady()) {
                status = PLAYING;
                this.sendStatusEvent();
            } else {
                status = PAUSED;
                this.sendStatusEvent();
            }
        }else{
            status = STOPPED;
            changeAudioFocus(false);
            this.sendStatusEvent();
        }
    }

    @Override
    public void onLoadError(IOException error) {
        status = ERROR;
        this.sendStatusEvent();
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {}

    private static String getDefaultUserAgent() {
        StringBuilder result = new StringBuilder(64);
        result.append("Dalvik/");
        result.append(System.getProperty("java.vm.version")); // such as 1.1.0
        result.append(" (Linux; U; Android ");

        String version = Build.VERSION.RELEASE; // "1.0" or "3.4b5"
        result.append(version.length() > 0 ? version : "1.0");

        // add the model for the release build
        if ("REL".equals(Build.VERSION.CODENAME)) {
            String model = Build.MODEL;
            if (model.length() > 0) {
                result.append("; ");
                result.append(model);
            }
        }
        String id = Build.ID; // "MASTER" or "M4-rc20"
        if (id.length() > 0) {
            result.append(" Build/");
            result.append(id);
        }
        result.append(")");
        return result.toString();
    }

    private void sendStatusEvent() {
        this.reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("RNAudioStreamerStatusChanged", status);
    }

    /**
    * AudioFocus
    *
    */

    private final OnAudioFocusChangeListener mAudioFocusListener = createOnAudioFocusChangeListener();

    private OnAudioFocusChangeListener createOnAudioFocusChangeListener() {
        return new OnAudioFocusChangeListener() {
            private boolean mLossTransient = false;
            private boolean wasPlaying = false;

            @Override
            public void onAudioFocusChange(int focusChange) {
                /*
                 * Pause playback during alerts and notifications
                 */
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        Log.i(TAG, "AUDIOFOCUS_LOSS");
                        // Pause playback
                        changeAudioFocus(false);
                        pause();
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        Log.i(TAG, "AUDIOFOCUS_LOSS_TRANSIENT");
                        // Pause playback
                        mLossTransient = true;
                        wasPlaying = isPlaying();
                        if (wasPlaying)
                            pause();
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        Log.i(TAG, "AUDIOFOCUS_GAIN: ");
                        // Resume playback
                        if (mLossTransient) {
                            if (wasPlaying)
                                play();
                            mLossTransient = false;
                        }
                        break;
                }
            }
        };
    }

    private void changeAudioFocus(boolean acquire) {
        final AudioManager am = (AudioManager)reactContext.getSystemService(reactContext.AUDIO_SERVICE);
        if (am == null)
            return;

        if (acquire) {
            if (!mHasAudioFocus) {
                final int result = am.requestAudioFocus(mAudioFocusListener,
                        AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    am.setParameters("bgm_state=true");
                    mHasAudioFocus = true;
                }
            }
        } else {
            if (mHasAudioFocus) {
                am.abandonAudioFocus(mAudioFocusListener);
                am.setParameters("bgm_state=false");
                mHasAudioFocus = false;
            }
        }
    }

}
