package com.abeez.firefling;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.amazon.whisperplay.fling.media.controller.DiscoveryController;
import com.amazon.whisperplay.fling.media.controller.RemoteMediaPlayer;
import com.amazon.whisperplay.fling.media.controller.RemoteMediaPlayer.FutureListener;
import com.amazon.whisperplay.fling.media.service.CustomMediaPlayer.PlayerSeekMode;
import com.amazon.whisperplay.fling.media.service.CustomMediaPlayer.StatusListener;
import com.amazon.whisperplay.fling.media.service.MediaPlayerStatus;
import com.amazon.whisperplay.fling.media.service.MediaPlayerStatus.MediaState;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.MobileAds;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MainActivity extends AppCompatActivity implements
        View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener
{

    private static final String BUNDLE_CURRENT_URL = "currentUrl";

    // Debugging TAG
    private static final String TAG = MainActivity.class.getName();

    // Settings to eventually build in
    // Switch off the "search" portion and only do direct urls
    private boolean allowSearch = true;

    // Set selected player from device picker
    private RemoteMediaPlayer mCurrentDevice;

    // Default value to set updating status interval from player
    private static final long MONITOR_INTERVAL = 1000L;
    // Callback from player to listen media status.(onStatusChange)
    private StatusListener mListener;
    // Store status information from player
    private Status mStatus = new Status();
    private final Object mStatusLock = new Object();

    // Discovery controller that triggers start/stop discovery
    private DiscoveryController mController;

    // Lock object for mDeviceList synchronization
    private final Object mDeviceListAvailableLock = new Object();
    // Set the discovered devices from Discovery controller
    private List<RemoteMediaPlayer> mDeviceList = new LinkedList<>();
    private List<RemoteMediaPlayer> mPickerDeviceList = new LinkedList<>();
    // Comparator to sort device list with alphabet device name order
    private RemoteMediaPlayerComp mComparator = new RemoteMediaPlayerComp();

    // Application menu
    private Menu mMenu;
    // Device picker adapter
    private ArrayAdapter<String> mPickerAdapter;
    // List of device picker items.
    private List<String> mPickerList = new ArrayList<>();

    // Progress(SeekBar) of media duration
    private SeekBar mSeekBar;
    private Long mMediaDuration = 0L;
    private boolean mDurationSet = false;
    // TextView to show total and current duration as number
    private TextView mTotalDuration;
    private TextView mCurrentDuration;

    private ImageView mPlayButton;
    private ImageView mPauseButton;

    private Button mClearUrlButton;
    private LinearLayout mMediaContainer;
    private TextView mVideoDetectedNotification;
    private ProgressBar mLoadingBar;
    private WebView mWebView;
    private EditText mUrlEditText;

    private boolean mAllowJavascript = true;
    private Set<String> mVideoList = new HashSet<>();
    private URI currentUrl;

    // Shared preference name
    private static final String APP_SHARED_PREF_NAME = "com.abeez.firefling";
    // Last stored player uuid from shared preference
    private String mLastPlayerId;
    // Error count for failing remote call
    private static final int MAX_ERRORS = 5;
    private int mErrorCount = 0;

    // Stops the automated duration update while user is actively seeking the video.
    private boolean mUserIsSeeking = false;

    private InterstitialAd mInterstitialAd;

    private DiscoveryController.IDiscoveryListener mDiscovery =
            new DiscoveryController.IDiscoveryListener() {

                @Override
                public void playerDiscovered(final RemoteMediaPlayer device) {
                    synchronized (mDeviceListAvailableLock) {
                        int threadId = android.os.Process.myTid();
                        if (mDeviceList.contains(device)) {
                            mDeviceList.remove(device);
                            Log.i(TAG, "["+threadId+"]"+"playerDiscovered(updating): " + device.getName());
                        } else {
                            Log.i(TAG, "["+threadId+"]"+"playerDiscovered(adding): " + device.getName());
                        }
                        mDeviceList.add(device);
                        // start rejoining with discovered device
                        if (mLastPlayerId != null && mCurrentDevice == null) {
                            if (device.getUniqueIdentifier().equalsIgnoreCase(mLastPlayerId)) {
                                new UpdateSessionTask().execute(device);
                            }
                        }
                        triggerUpdate();
                    }
                }

                @Override
                public void playerLost(final RemoteMediaPlayer device) {
                    synchronized (mDeviceListAvailableLock) {
                        if (mDeviceList.contains(device)) {
                            int threadId = android.os.Process.myTid();
                            Log.i(TAG, "["+threadId+"]"+"playerLost(removing): " + device.getName());
                            if (device.equals(mCurrentDevice) && mListener != null) {
                                Log.i(TAG, "["+threadId+"]"+"playerLost(removing): " + mListener.toString());
                                device.removeStatusListener(mListener);
                                mCurrentDevice = null;
                            }
                            mDeviceList.remove(device);
                            triggerUpdate();
                        }
                    }
                }

                @Override
                public void discoveryFailure() {
                    Log.e(TAG, "Discovery Failure");
                }

                private void triggerUpdate() {
                    // It should be run in main thread since it is updating Adapter.
                    runOnUiThread(()-> {
                        mPickerDeviceList = mDeviceList;
                        Collections.sort(mPickerDeviceList, mComparator);
                        mPickerList.clear();
                        for (RemoteMediaPlayer device : mPickerDeviceList) {
                            mPickerList.add(device.getName());
                        }
                        mPickerAdapter.notifyDataSetChanged();
                        // Calling onPrepareOptionsMenu() to update picker icon
                        invalidateOptionsMenu();
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.e(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Hide Home icon
        if( getActionBar() != null ) {
            getActionBar().setDisplayShowHomeEnabled(false);
        }

        // Initialize UI resources
        mTotalDuration = findViewById(R.id.tv_totalDuration);
        mCurrentDuration = findViewById(R.id.tv_currentDuration);
        mSeekBar = findViewById(R.id.sb_seekBar);
        mPlayButton = findViewById(R.id.iv_play);
        mPauseButton = findViewById(R.id.iv_pause);
        mMediaContainer = findViewById(R.id.ll_mediaContainer);
        mVideoDetectedNotification = findViewById(R.id.tv_videoList);
        mLoadingBar = findViewById(R.id.pb_loadingBar);
        mWebView = findViewById(R.id.wv_browser);
        mClearUrlButton = findViewById(R.id.b_clear_url);
        mUrlEditText = findViewById(R.id.et_url_input);

        // Create DiscoveryController
        mController = new DiscoveryController(this);

        AdBlocker.init(this);
        MobileAds.initialize(this, "ca-app-pub-6217417570479825~5689587790");

        mInterstitialAd = new InterstitialAd(this);
        mInterstitialAd.setAdUnitId("ca-app-pub-3940256099942544/1033173712");
        //mInterstitialAd.setAdUnitId("ca-app-pub-6217417570479825/8493868628");

        // Load saved instance items
        if(savedInstanceState != null) {
            if(savedInstanceState.getString(BUNDLE_CURRENT_URL) != null) {
                currentUrl = Utils.convertStringToUri(savedInstanceState.getString(BUNDLE_CURRENT_URL));
            }

            mWebView.restoreState(savedInstanceState);
        }
        else {
            currentUrl = Utils.convertStringToUri("https://www.google.com");
            mWebView.loadUrl(currentUrl.toString());
        }

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        allowSearch = sharedPreferences.getBoolean(getString(R.string.pref_do_search_key),
                getResources().getBoolean(R.bool.pref_do_search_default));

        // Register the listener
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onResume() {
        Log.e(TAG, "onResume");
        super.onResume();


        // Playback controller will be enabled when connectionUpdate succeed.
        setPlaybackControlWorking(false);
        mListener = new Monitor();
        // Set if last player was saved
        retrieveLastPlayerIfExist();
        // Start Discovery Controller
        Log.i(TAG, "onResume - start Discovery");
        mController.start("amzn.thin.pl", mDiscovery);
        // Create device picker adapter
        mPickerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_activated_1, mPickerList);

        // When user moves progress bar, seek absolute position from current player.
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean userInitiated) {
                if( userInitiated ) {
                    runOnUiThread(() -> mCurrentDuration.setText(convertTime(progress)));
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                mUserIsSeeking = true;
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mCurrentDevice != null) {
                    Log.i(TAG, "SeekBar(Absolute seek) - " + convertTime(seekBar.getProgress()));
                    mCurrentDevice.seek(PlayerSeekMode.Absolute, seekBar.getProgress())
                            .getAsync(new ErrorResultHandler("Seek...","Error Seeking"));
                }

                mUserIsSeeking = false;
            }
        });

        mVideoDetectedNotification.setOnClickListener((view) -> {
            if( mVideoList.size() == 1 ) {
                fling(mCurrentDevice, mVideoList.toArray(new String[]{})[0]);
            }
            else {
                AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
                alertBuilder.setTitle(R.string.pick_a_video);
                CharSequence videos[] = mVideoList.toArray(new String[]{});
                alertBuilder.setItems(videos, (dialogInterface, itemSelected) -> {
                    Log.e(TAG, "Flinging: " + videos[itemSelected]);
                    fling(mCurrentDevice, videos[itemSelected].toString());
                });

                alertBuilder.show();
            }
        });

        mClearUrlButton.setOnClickListener((view) -> {
            mUrlEditText.getText().clear();
            mUrlEditText.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if( imm != null ) {
                imm.showSoftInput(mUrlEditText, InputMethodManager.SHOW_IMPLICIT);
            }
        });

        if(! allowSearch ) {
            mUrlEditText.setHint(R.string.url_no_search_hint);
        }
        mUrlEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int before, int count) {
                if( charSequence.length() == 0 ) {
                    mClearUrlButton.setVisibility(View.INVISIBLE);
                }
                else {
                    mClearUrlButton.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void afterTextChanged(Editable editable) {}
        });

        mUrlEditText.setText(currentUrl.toString());
        mUrlEditText.setSelectAllOnFocus(true);

        mUrlEditText.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if( actionId == EditorInfo.IME_ACTION_GO )
            {
                String userInput = textView.getText().toString();
                URI uri = Utils.convertStringToUri(userInput, allowSearch);
                if( uri == null ) {
                    makeShortToast(getString(R.string.invalid_url_format));
                    return false;
                }

                currentUrl = uri;
                Log.i(TAG, "Going to :: " + currentUrl);
                mWebView.loadUrl(currentUrl.toString());

                InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if( inputManager != null ) {
                    View currentView = getCurrentFocus();
                    if( currentView != null ) {
                        inputManager.hideSoftInputFromWindow(currentView.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                    }
                }

                mLoadingBar.setProgress(0);
                mWebView.requestFocus();
                return true;
            }

            return false;
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mLoadingBar.setProgress(newProgress);

                if( newProgress >= 100 ) {
                    mLoadingBar.setVisibility(View.INVISIBLE);
                }

                super.onProgressChanged(view, newProgress);
            }
        });
        mWebView.addJavascriptInterface(new MyJavaScriptInterface(), "HtmlViewer");

        WebSettings webViewSettings = mWebView.getSettings();
        webViewSettings.setSupportZoom(true);
        webViewSettings.setJavaScriptEnabled(mAllowJavascript);
        webViewSettings.setJavaScriptCanOpenWindowsAutomatically(false);
        webViewSettings.setMediaPlaybackRequiresUserGesture(false);
        webViewSettings.setBuiltInZoomControls(true);
        webViewSettings.setDisplayZoomControls(false);
        webViewSettings.setUseWideViewPort(false);
        webViewSettings.setDomStorageEnabled(true);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);

                mLoadingBar.setVisibility(View.VISIBLE);
                mUrlEditText.setText(url);
            }



            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                Log.e(TAG, "Checking " + url.getHost());
                if( AdBlocker.isAd(url.getHost()) ) {
                    Log.e(TAG, "Blocking: " + url);
                    return AdBlocker.createEmptyResource();
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                currentUrl = Utils.convertStringToUri(mWebView.getUrl());
                mUrlEditText.setText(currentUrl.toString());
                Log.e(TAG, "URL: " + currentUrl.toString());
                view.loadUrl("javascript:window.HtmlViewer.showHTML" +
                        "('<html>' + document.getElementsByTagName('html')[0].innerHTML+'</html>');");

                setVideoDetectedVisibility();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.d(TAG, "Trying to load :: " + url);
                URI uriLoading = Utils.convertStringToUri(url);
                if( uriLoading != null ) {
                    Log.e(TAG, "Checking " + uriLoading.getHost());
                    if( AdBlocker.isAd(uriLoading.getHost()) ) {
                        Log.e(TAG, "Blocking: " + url);
                        return true;
                    }
                    if(!(
                            "https".equals(uriLoading.getScheme()) ||
                                    "http".equals(uriLoading.getScheme()) ||
                                    ("javascript".equals(uriLoading.getScheme()) && mAllowJavascript) ))
                    {
                        Log.e(TAG, "Scheme: " + uriLoading.getScheme());
                        return true;
                    }

                    mVideoList.clear();
                    mVideoDetectedNotification.setVisibility(View.GONE);
                    setMediaContainerVisibility(View.GONE);
                    mUrlEditText.setText(url);
                    currentUrl = uriLoading;
                    return false;
                }

                Log.e(TAG, "Not a real URI: " + url);
                return true;
            }

            @Override
            public void onLoadResource(WebView view, String url) {
                URI uri = Utils.convertStringToUri(url);
                if( uri != null ) {
                    if( Utils.isVideoLink(uri) ) {
                        Log.e(TAG, "Video found: " + url);
                        addVideo(url);
                    }
                }

                super.onLoadResource(view, url);
            }
        });

        // initialize error count
        mErrorCount = 0;
        Log.e(TAG, "Done with onResume()");

        mInterstitialAd.loadAd(new AdRequest.Builder().build());
        mInterstitialAd.setAdListener(new AdListener() {
            @Override
            public void onAdClosed() {
                mInterstitialAd.loadAd(new AdRequest.Builder().build());
            }
        });
    }

    public void setVideoDetectedVisibility() {
        runOnUiThread(() -> {
            if( mVideoList.size() == 0 ) {
                mVideoDetectedNotification.setVisibility(View.GONE);
                return;
            }

            if( mVideoList.size() == 1 ) {
                mVideoDetectedNotification.setText(R.string.one_video_found);
            }
            else if( mVideoList.size() > 1 ) {
                mVideoDetectedNotification.setText(R.string.multiple_videos_found);
            }

            if( mVideoDetectedNotification.getVisibility() != View.VISIBLE ) {
                mVideoDetectedNotification.setVisibility(View.VISIBLE);
            }
        });
    }

    private void setMediaContainerVisibility(int visibility) {
        synchronized(mStatusLock) {
            MediaState mState = mStatus.mState;
            if( mState == null ) {
                return;
            }

            if( visibility == View.GONE || visibility == View.INVISIBLE) {
                if( mState == MediaState.ReadyToPlay ||
                    mState == MediaState.Playing ||
                    mState == MediaState.Paused ||
                    mState == MediaState.Seeking )
                {
                    //ignore
                    Log.i(TAG, "Not hiding the media controller while media is loaded.");
                    return;
                }
            }
            else if( visibility == View.VISIBLE ) {
                if( mState == MediaState.NoSource ||
                    mState == MediaState.PreparingMedia ||
                    mState == MediaState.Finished ||
                    mState == MediaState.Error)
                {
                    //ignore
                    Log.i(TAG, "Not showing the media controller while there is no media to play.");
                    return;
                }
            }

            mMediaContainer.setVisibility(visibility);
        }
    }


    @Override
    public void onBackPressed() {
        if( mWebView.canGoBack()) {
            mVideoList.clear();
            mVideoDetectedNotification.setVisibility(View.GONE);
            setMediaContainerVisibility(View.GONE);
            mWebView.goBack();
        }
        else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(BUNDLE_CURRENT_URL, currentUrl.toString());
        mWebView.saveState(outState);
        super.onSaveInstanceState(outState);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.e(TAG, "onCreateOptionsMenu");
        this.mMenu = menu;
        getMenuInflater().inflate(R.menu.fling, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem flingButton = menu.findItem(R.id.menu_fling);
        if (mPickerDeviceList.size() > 0) {
            if (mCurrentDevice != null) {
                flingButton.setIcon(R.drawable.ic_whisperplay_default_blue_light_48dp);
                setPlaybackControlWorking(true);
            } else {
                flingButton.setIcon(R.drawable.ic_whisperplay_default_light_48dp);
                setPlaybackControlWorking(false);
            }
            setPickerIconVisibility(true);
        } else {
            flingButton.setIcon(R.drawable.ic_whisperplay_default_light_48dp);
            setPickerIconVisibility(false);
            setPlaybackControlWorking(false);
        }
        return true;
    }

    @Override
    protected void onPause() {
        Log.e(TAG, "onPause");
        if (mCurrentDevice != null) {
            Log.i(TAG, "onPause - removeStatusListener:mListener=" + mListener.toString());
            try {
                storeLastPlayer(true);
                Log.i(TAG, "onPause - removeStatusListener: start t=" + System.currentTimeMillis());
                mCurrentDevice.removeStatusListener(mListener).get(3000, TimeUnit.MILLISECONDS);
                Log.i(TAG, "onPause - removeStatusListener: finish t=" + System.currentTimeMillis());
            } catch (InterruptedException e) {
                Log.e(TAG, "InterruptedException. msg =" + e.getMessage());
            } catch (ExecutionException e) {
                Log.e(TAG, "ExecutionException. msg =" + e.getMessage());
            } catch (TimeoutException e) {
                Log.e(TAG, "TimeoutException. msg =" + e.getMessage());
            } finally {
                clean();
            }
        } else {
            storeLastPlayer(false);
            clean();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        Log.e(TAG, "onStop");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        Log.e(TAG, "onDestroy");
        super.onDestroy();

        // Unregister MainActivity as an OnPreferenceChangedListener to avoid any memory leaks.
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if( key.equals(getString(R.string.pref_do_search_key)) ) {
            allowSearch = sharedPreferences.getBoolean(getString(R.string.pref_do_search_key),
                    getResources().getBoolean(R.bool.pref_do_search_default));
        }
    }

    private void addVideo(String url) {
        mVideoList.add(url);
        setVideoDetectedVisibility();
    }

    private void resetDuration() {
        Log.i(TAG, "resetDuration");
        mMediaDuration = 0L;
        mSeekBar.setProgress(0);
        mSeekBar.setMax(0);
        mDurationSet = false;
        mCurrentDuration.setText(convertTime(0));
        mTotalDuration.setText(convertTime(0));
    }

    private void setPlaybackControlWorking(boolean enable) {
        Log.i(TAG, "setPlaybackControlWorking:" + (enable ? "enable" : "disable"));
        if( enable ) {
            setMediaContainerVisibility(View.VISIBLE);
        }
        else {
            setMediaContainerVisibility(View.GONE);
            mCurrentDuration.setText(convertTime(0));
            mTotalDuration.setText(convertTime(0));
            mSeekBar.setMax(0);
            mSeekBar.setProgress(0);
        }
    }

    private void setPickerIconVisibility(boolean enable) {
        Log.i(TAG, "setPickerIconVisibility: " + (enable ? "enable" : "disable"));
        MenuItem flingButton = mMenu.findItem(R.id.menu_fling);
        flingButton.setVisible(enable);
    }

    @Override
    public void onClick(View view) {
        MediaState state;
        synchronized (mStatusLock) {
            state = mStatus.mState;
        }
        switch (view.getId()) {

            case R.id.iv_play:
                Log.i(TAG, "onClick - PlayButton");
                if (state == MediaState.Paused || state == MediaState.ReadyToPlay) {
                    Log.i(TAG, "onClick - Start doPlay");
                    doPlay();
                }
                break;
            case R.id.iv_pause:
                Log.i(TAG, "onClick - PauseButton");
                doPause();
                break;
            case R.id.iv_stop:
                Log.i(TAG, "onClick - StopButton");
                doStop();
                break;
            case R.id.iv_forward:
                Log.i(TAG, "onClick - ForwardButton");
                doFore();
                break;
            case R.id.iv_backward:
                Log.i(TAG, "onClick - BackwardButton");
                doBack();
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.menu_fling) {
            if (mCurrentDevice == null) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.menu_fling))
                        .setAdapter(mPickerAdapter, (dialogInterface, index) -> connectionUpdate(mPickerDeviceList.get(index)))
                        .show();
                return true;
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(mCurrentDevice.getName())
                        .setPositiveButton(R.string.btn_disconnect,
                                (dialogInterface, i) -> {
                                    connectionUpdate(null);
                                    dialogInterface.dismiss();
                                })
                        .setNegativeButton(R.string.cancel,
                                (dialogInterface, i) -> dialogInterface.dismiss())
                        .show();
                return true;
            }
        }
        else if( id == R.id.action_settings ) {
            Intent startSettingsActivity = new Intent(this, SettingsActivity.class);
            startActivity(startSettingsActivity);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void connectionUpdate(final RemoteMediaPlayer target) {
        new ConnectionUpdateTask().execute(target);
    }

    private void setStatusText() {
        // This method deals with UI on main thread.
        runOnUiThread(() -> {
            synchronized (mStatusLock) {
                switch (mStatus.mState) {
                    case NoSource:
                        break;
                    case PreparingMedia:
                        Log.i(TAG, "setStatusText - PreparingMedia");
                        //mCurrentStatusView.setText(getString(R.string.media_preping));
                        break;
                    case ReadyToPlay:
                        Log.i(TAG, "setStatusText - ReadyToPlay");
                        //mCurrentStatusView.setText(getString(R.string.media_readytoplay));
                        break;
                    case Playing:
                        Log.i(TAG, "setStatusText - Playing");
                        if (!mDurationSet) {
                            Log.i(TAG, "setStatusText - Playing: ReadyToPlay was missed." +
                                    " duration needs to be set.");
                            new TotalDurationUpdateTask().execute();

                        }

                        //Update progress session
                        if (mMediaDuration > 0 && mDurationSet && !mUserIsSeeking) {
                            Log.i(TAG, "setStatusText - Playing: Set Progress");
                            mTotalDuration.setText(String.valueOf(convertTime(mMediaDuration)));
                            mCurrentDuration.setText(String.valueOf(convertTime(mStatus.mPosition)));
                            mSeekBar.setProgress((int) mStatus.mPosition);
                        }

                        break;
                    case Paused:
                        Log.i(TAG, "setStatusText - Paused");
                        if (!mDurationSet) {
                            new TotalDurationUpdateTask().execute();
                            new CurrentPositionUpdateTask().execute();
                        }
                        break;
                    case Finished:
                        Log.i(TAG, "setStatusText - Finished");
                        resetDuration();
                        break;
                    case Seeking:
                        Log.i(TAG, "setStatusText - Seeking");
                        break;
                    case Error:
                        Log.i(TAG, "setStatusText - Error");
                        setMediaContainerVisibility(View.GONE);
                        break;
                    default:
                        break;
                }
            }
        });
    }

    private void handleFailure( Throwable throwable, final String msg, final boolean extend ) {
        Log.e(TAG, msg, throwable);
        final String exceptionMessage = throwable.getMessage();

        mErrorCount = mErrorCount+1;
        if (mErrorCount > MAX_ERRORS) {
            errorMessagePopup(msg + (extend ? exceptionMessage : ""));
        }
    }

    private void errorMessagePopup(final String message) {
        runOnUiThread(() -> {
            Log.e(TAG, "errorMessagePopup: Showing the error message.");
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.communication_error))
                    .setMessage(message)
                    .setNeutralButton(getString(R.string.btn_close),
                            (dialogInterface, i) -> dialogInterface.dismiss())
                    .show();
            if (mCurrentDevice != null) {
                Log.e(TAG, "errorMessagePopup: removeStatusListener. set current device to null");
                mCurrentDevice.removeStatusListener(mListener);
                mCurrentDevice = null;
            }
            resetDuration();
            setPlaybackControlWorking(false);
            invalidateOptionsMenu();
        });
    }

    private void initializeFling(final RemoteMediaPlayer target) {
        Log.i(TAG, "initializeFling - target: " + target.toString());
        mCurrentDevice = target;
        mStatus.clear();
        resetDuration();
    }

    private void makeShortToast(final String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void makeLongToast(final String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private void fling(final RemoteMediaPlayer target, final String name) {
        if( target == null ) {
            makeLongToast(getString(R.string.must_connect_to_device));
            return;
        }

        initializeFling(target);
        Log.i(TAG, "try setPositionUpdateInterval: " + MONITOR_INTERVAL);
        mCurrentDevice.setPositionUpdateInterval(MONITOR_INTERVAL).getAsync(
                new ErrorResultHandler("setPositionUpdateInterval",
                        getString(R.string.error_setting_update_interval), true));
        Log.i(TAG, "try setMediaSource: url - " + name );
        mCurrentDevice.setMediaSource(name, "video", true, false).getAsync(
                new FutureResultHandler(
                        () -> {
                            runOnUiThread(()->{
                                if( mInterstitialAd.isLoaded()) {
                                    mInterstitialAd.show();
                                }
                            });
                        }, "setMediaSource", getString(R.string.error_attempting_to_play))
                );
                //new ErrorResultHandler("setMediaSource", , true));
    }

    private void doPlay() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doPlay...");
            mCurrentDevice.play().getAsync(
                    new FutureResultHandler(
                            () -> {
                                runOnUiThread(()->{
                                    mPlayButton.setVisibility(View.INVISIBLE);
                                    mPauseButton.setVisibility(View.VISIBLE);
                                });

                            }, "doPlay", getString(R.string.error_playing))
            );
        }
    }

    private void doPause() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doPause...");
            mCurrentDevice.pause().getAsync(
                    new FutureResultHandler(
                            () -> {
                                runOnUiThread(()->{
                                    mPlayButton.setVisibility(View.VISIBLE);
                                    mPauseButton.setVisibility(View.INVISIBLE);
                                });
                            }, "doPause", getString(R.string.error_pausing)
                    )
            );
        }
    }

    private void doStop() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doStop...");
            mCurrentDevice.stop().getAsync(
                    new FutureResultHandler(
                            () -> {
                                runOnUiThread(() -> {
                                    mMediaContainer.setVisibility(View.GONE);
                                    setVideoDetectedVisibility();
                                    mStatus.clear();
                                    resetDuration();
                                });
                            }, "doStop", getString(R.string.error_stopping)
                    )
            );
        }
    }

    private void doFore() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doFore - seek");
            mCurrentDevice.seek(PlayerSeekMode.Relative, 10000).getAsync(
                    new ErrorResultHandler("doFore", getString(R.string.error_seeking)));
        }
    }

    private void doBack() {
        if (mCurrentDevice != null) {
            Log.i(TAG, "try doBack - seek");
            mCurrentDevice.seek(PlayerSeekMode.Relative, -10000).getAsync(
                    new ErrorResultHandler("doBack", getString(R.string.error_seeking)));
        }
    }

    private void retrieveLastPlayerIfExist(){
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(
                APP_SHARED_PREF_NAME, Context.MODE_PRIVATE);
        mLastPlayerId = preferences.getString("lastPlayerId", null);
        Log.i(TAG, "retrieveLastPlayerIfExist - lastPlayerId=" + mLastPlayerId);
    }

    private void storeLastPlayer(boolean value) {
        SharedPreferences preferences = getApplicationContext().getSharedPreferences(
                APP_SHARED_PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        if (value) {
            if (mCurrentDevice != null) {
                editor.putString("lastPlayerId", mCurrentDevice.getUniqueIdentifier());
                editor.apply();
                Log.i(TAG, "storeLastPlayer - id:" + mCurrentDevice.getUniqueIdentifier());
            }
        } else {
            Log.i(TAG, "storeLastPlayer - remove id and clear");
            editor.clear();
            editor.apply();
        }
    }

    private static String convertTime(long time) {
        long totalSecs = time / 1000;
        long hours = totalSecs / 3600;
        long minutes = (totalSecs / 60) % 60;
        long seconds = totalSecs % 60;
        String hourString = (hours == 0) ? "00" : ((hours < 10) ? "0" + hours : "" + hours);
        String minString = (minutes == 0) ? "00" : ((minutes < 10) ? "0" + minutes : "" + minutes);
        String secString = (seconds == 0) ? "00" : ((seconds < 10) ? "0" + seconds : "" + seconds);

        return hourString + ":" + minString + ":" + secString;
    }

    private void clean() {
        Log.i(TAG, "clean - calling mController.stop()");
        mController.stop();
        mCurrentDevice = null;
        mDeviceList.clear();
        mPickerDeviceList.clear();
        resetDuration();
        setPickerIconVisibility(false);
        setPlaybackControlWorking(false);
    }

    private static class Status {
        long mPosition;
        MediaState mState;
        MediaPlayerStatus.MediaCondition mCond;

        synchronized void clear() {
            mPosition = -1L;
            mState = MediaState.NoSource;
        }
    }

    private class Monitor implements StatusListener {

        @Override
        public void onStatusChange(MediaPlayerStatus status, long position) {
            if (mCurrentDevice != null) {
                synchronized (mStatusLock) {
                    mStatus.mState = status.getState();
                    mStatus.mCond = status.getCondition();
                    mStatus.mPosition = position;
                    Log.i(TAG, "State Change state=" + mStatus.mState
                            + " Position=" + convertTime(position));
                    if (mStatus.mState == MediaState.ReadyToPlay) {
                        runOnUiThread(MainActivity.this::resetDuration);
                        new TotalDurationUpdateTask().execute();
                    }
                }
                setStatusText();
            }
        }
    }

    private class ConnectionUpdateTask extends AsyncTask<RemoteMediaPlayer, Void, Integer> {
        private static final String TAG = "ConnectionUpdateTask";

        @Override
        protected Integer doInBackground(RemoteMediaPlayer... remoteMediaPlayers) {
            int threadId = android.os.Process.myTid();
            if (remoteMediaPlayers[0] != null) { // Connect
                RemoteMediaPlayer target = remoteMediaPlayers[0];
                try {
                    Log.i(TAG, "[" + threadId + "]" + "ConnectionUpdateTask:addStatusListener"
                            + ":target=" + target);
                    target.addStatusListener(mListener).get();
                    // Set current device after remote call succeed.
                    mCurrentDevice = target;
                    Log.i(TAG, "["+threadId+"]"+"ConnectionUpdateTask:set current device"
                            +":currentDevice="+target);
                    return 0;
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return 2;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return 2;
                }
            } else { // Disconnect
                try {
                    if (mCurrentDevice != null) {
                        Log.i(TAG, "["+threadId+"]"+"ConnectionUpdateTask:removeStatusListener" +
                                ":mCurrentDevice="+mCurrentDevice+ "mListener="+mListener);
                        mCurrentDevice.removeStatusListener(mListener).get();
                    }
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                }

                return 1;
            }
        }

        @Override
        protected void onPostExecute(Integer result) {
            MenuItem item = mMenu.findItem(R.id.menu_fling);
            switch (result) {
                case 0:
                    // after connection
                    mErrorCount = 0;
                    Log.i(TAG, "[main]" + "ConnectionUpdateTask:onPostExecute: connection");
                    item.setIcon(R.drawable.ic_whisperplay_default_blue_light_48dp);
                    invalidateOptionsMenu();
                    new UpdateSessionTask().execute(mCurrentDevice);
                    break;
                case 1:
                    // after disconnection
                    Log.i(TAG, "[main]" + "ConnectionUpdateTask:onPostExecute: disconnection");
                    item.setIcon(R.drawable.ic_whisperplay_default_light_48dp);
                    mCurrentDevice = null;
                    invalidateOptionsMenu();
                    setPlaybackControlWorking(false);
                    resetDuration();
                    break;
                case 2:
                    // error handle
                    Log.i(TAG, "[main]" + "ConnectionUpdateTask:onPostExecute: error handle");
                    errorMessagePopup(getString(R.string.error_connecting_to_device));
                    break;
            }
        }
    }

    private class UpdateSessionTask extends AsyncTask<RemoteMediaPlayer, Void, MediaPlayerStatus> {
        private static final String TAG = "UpdateSessionTask";
        RemoteMediaPlayer target = null;

        @Override
        protected MediaPlayerStatus doInBackground(RemoteMediaPlayer... remoteMediaPlayers) {
            target = remoteMediaPlayers[0];
            int threadId = android.os.Process.myTid();
            Log.i(TAG, "["+threadId+"]"+"UpdateSessionTask:found match: " + target.getName());
            try {
                Log.i(TAG, "["+threadId+"]"+"UpdateSessionTask:getStatus");
                return target.getStatus().get();
            } catch (InterruptedException e) {
                Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                target = null;
                return null;
            } catch (ExecutionException e) {
                Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                target = null;
                return null;
            }
        }

        @Override
        protected void onPostExecute(MediaPlayerStatus mediaPlayerStatus) {
            if (mediaPlayerStatus != null) {
                mCurrentDevice = target;
                Log.i(TAG, "[main]" + "UpdateSessionTask:onPostExecute:set current device:"
                        +mCurrentDevice.toString());
                mCurrentDevice.addStatusListener(mListener);
                synchronized (mStatusLock) {
                    mStatus.mState = mediaPlayerStatus.getState();
                    mStatus.mCond = mediaPlayerStatus.getCondition();
                }
                setStatusText();
                MenuItem item = mMenu.findItem(R.id.menu_fling);
                item.setIcon(R.drawable.ic_whisperplay_default_blue_light_48dp);
                setPlaybackControlWorking(true);
                invalidateOptionsMenu();
            } else {
                Log.i(TAG, "[main]" + "UpdateSessionTask:onPostExecute:skip rejoin");
            }
        }
    }

    private class CurrentPositionUpdateTask extends AsyncTask<Void, Void, Long> {
        private static final String TAG = "CurrentPosUpdateTask";

        @Override
        protected Long doInBackground(Void... voids) {
            if (mCurrentDevice != null) {
                int threadId = android.os.Process.myTid();
                try {
                    Log.i(TAG, "["+threadId+"]"+"CurrentPositionUpdateTask:getPosition");
                    return mCurrentDevice.getPosition().get();
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return null;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            if (result != null) {
                Log.i(TAG, "[main]"+"CurrentPositionUpdateTask:onPostExecute:");
                mSeekBar.setProgress(result.intValue());
                mCurrentDuration.setText(convertTime(result.intValue()));
            } else {
                Log.i(TAG, "[main]" +"CurrentPositionUpdateTask:onPostExecute:result is null");
            }
        }
    }
    private class TotalDurationUpdateTask extends AsyncTask<Void, Void, Long> {
        private static final String TAG = "TotalDurationUpdateTask";

        @Override
        protected Long doInBackground(Void... voids) {
            if (mCurrentDevice != null) {
                int threadId = android.os.Process.myTid();
                try {
                    Log.e(TAG, "["+threadId+"]"+"TotalDurationUpdateTask:getDuration");
                    return mCurrentDevice.getDuration().get();
                } catch (InterruptedException e) {
                    Log.e(TAG, "["+threadId+"]"+"InterruptedException msg=" + e);
                    return null;
                } catch (ExecutionException e) {
                    Log.e(TAG, "["+threadId+"]"+"ExecutionException msg=" + e);
                    return null;
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Long result) {
            Log.e(TAG, "Total Duration: " + result);
            if (result != null) {
                Log.i(TAG, "[main]" + "TotalDurationUpdateTask:onPostExecute");
                mMediaDuration = result;
                mSeekBar.setMax(result.intValue());
                if (mMediaDuration > 0) {
                    Log.i(TAG, "[main]" + "TotalDurationUpdateTask:onPostExecute:setTotalDuration");
                    mTotalDuration.setText(String.valueOf(convertTime(mMediaDuration)));
                    mDurationSet = true;
                }
                setPlaybackControlWorking(true);
            } else {
                Log.i(TAG, "[main]" +"TotalDurationUpdateTask:onPostExecute:result is null");
            }
        }
    }

    private class ErrorResultHandler implements FutureListener<Void> {
        private String mCommand;
        private String mMsg;
        private boolean mExtend;

        ErrorResultHandler(String command, String msg) {
            this(command, msg, false);
        }

        ErrorResultHandler(String command, String msg, boolean extend) {
            mCommand = command;
            mMsg = msg;
            mExtend = extend;
        }

        @Override
        public void futureIsNow(Future<Void> result) {
            try {
                result.get();
                mErrorCount = 0;
                Log.i(TAG, mCommand + ": successful");
            } catch(ExecutionException e) {
                handleFailure(e.getCause(), mMsg, mExtend);
            } catch(Exception e) {
                handleFailure(e, mMsg, mExtend);
            }
        }
    }

    private class FutureResultHandler implements FutureListener<Void> {
        private Runnable success;
        private String mCommand;
        private String mMsg;
        private boolean mExtend = false;

        FutureResultHandler(Runnable success, final String mCommand, final String mMsg) {
            this.success = success;
            this.mCommand = mCommand;
            this.mMsg = mMsg;
        }

        @Override
        public void futureIsNow(Future<Void> result) {
            try {
                result.get();
                mErrorCount = 0;
                Log.i(TAG, mCommand + ": successful");
                success.run();
            }
            catch(ExecutionException | InterruptedException | CancellationException e) {
                handleFailure(e, mMsg, mExtend);
            }
        }
    }

    private static class RemoteMediaPlayerComp implements Comparator<RemoteMediaPlayer> {
        @Override
        public int compare(RemoteMediaPlayer player1, RemoteMediaPlayer player2) {
            return player1.getName().compareTo(player2.getName());
        }
    }

    class MyJavaScriptInterface {
        private static final String TAG = "MyJavaScriptInterface";

        @JavascriptInterface
        public void showHTML(String html) {
            Document doc = Jsoup.parse(html, currentUrl.getHost());

            Elements links = doc.select("a[href]");
            for (Element e : links) {
                URI uri = Utils.convertStringToUri(e.attr("href"));
                if( uri == null || uri.getPath() == null ) {
                    continue;
                }

                if( Utils.isVideoLink(uri) ) {
                    addVideo(uri.toString());
                    Log.e(TAG, "Video: " + uri.toString());
                }
            }
        }
    }
}