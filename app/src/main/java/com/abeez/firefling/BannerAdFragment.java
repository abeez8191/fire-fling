package com.abeez.firefling;

import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;

public class BannerAdFragment extends Fragment {

    private static final String TAG = "BannerAdFragment";
    private AdView mAdView;
    private Handler mAdRefreshHandler = new Handler();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.ad_fragment, container, false);
        mAdView = rootView.findViewById(R.id.av_bannerAd);

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        mAdView.loadAd(new AdRequest.Builder().build());
        mAdView.setAdListener(new AdListener() {
            @Override
            public void onAdClosed() {
                mAdView.loadAd(new AdRequest.Builder().build());
            }

            @Override
            public void onAdLoaded() {
                getActivity().runOnUiThread(() -> {
                    mAdRefreshHandler.postDelayed(()-> {
                        Log.e(TAG, "Updating the ad now.");
                        mAdView.loadAd(new AdRequest.Builder().build());
                    }, 5 * 60 * 1000);
                });
            }
        });
    }

    @Override
    public void onPause() {
        mAdRefreshHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }
}
