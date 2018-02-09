package com.abeez.firefling;

import android.content.Context;
import android.os.AsyncTask;
import android.support.annotation.WorkerThread;
import android.util.Log;
import android.webkit.WebResourceResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;


class AdBlocker {
    private static final String TAG = "AdBlocker";
    private static final String AD_HOSTS_FILE = "adDomains.txt";
    private static final String AD_HOSTS_URL = "https://pgl.yoyo.org/as/serverlist.php?hostformat=nohtml&showintro=1";
    private static final Set<String> AD_HOSTS = new HashSet<>();

    static void init(Context context) {
        new LoadAdBlockerTask(context).execute();
    }

    @WorkerThread
    private static void loadFromFile(Context context) throws IOException{
        try(FileInputStream inputStream = context.openFileInput(AD_HOSTS_FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream)))
        {
            String line;
            while( (line = reader.readLine()) != null ) {
                AD_HOSTS.add(line);
            }
        }
    }

    @WorkerThread
    private static void downloadAdHostsFile(Context context) throws IOException{
        URL adHostsUrl;
        try {
            adHostsUrl = new URL(AD_HOSTS_URL);
        }
        catch(MalformedURLException e) {
            return;
        }

        FileOutputStream fos = null;
        HttpURLConnection urlConnection = null;
        try {
            urlConnection = (HttpURLConnection) adHostsUrl.openConnection();
            fos = context.openFileOutput(AD_HOSTS_FILE, Context.MODE_PRIVATE);

            try(InputStream in = urlConnection.getInputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(in)))
            {
                String line;
                while((line = reader.readLine()) != null ) {
                    fos.write(line.getBytes());
                    fos.write("\n".getBytes());
                }
            }
        }
        finally {
            if( urlConnection != null ) {
                urlConnection.disconnect();
            }
            if( fos != null ) {
                fos.close();
            }
        }
    }

    private static boolean fileExists(String fileName, Context context) {
        File file = context.getFileStreamPath(fileName);
        return file.exists();
    }

    static boolean isAd(String hostname) {
        //Log.e("AdBlocker", "Checking out hostname = " + hostname);
        if(hostname == null || hostname.isEmpty() ) {
            return false;
        }

        if( AD_HOSTS.contains(hostname) ) {
            Log.i("AdBlocker", "Blocking " + hostname);
            return true;
        }

        int index = hostname.indexOf(".");
        return index > 0 &&
               (index + 1) < hostname.length() &&
                isAd(hostname.substring(index + 1));
    }

    static WebResourceResponse createEmptyResource() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream("".getBytes()));
    }

    private static class LoadAdBlockerTask extends AsyncTask<Void, Void, Void> {
        private final WeakReference<Context> contextRef;

        public LoadAdBlockerTask(Context context) {
            this.contextRef = new WeakReference<>(context);
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Context context = contextRef.get();
            try {
                if( fileExists( AD_HOSTS_FILE, context ) ) {
                    Log.i(TAG, "Loading ad hosts in file.");
                    loadFromFile(context);
                }
                else {
                    Log.i(TAG, "Downloading the ad hosts file.");
                    downloadAdHostsFile(context);
                    loadFromFile(context);
                }
                Log.e(TAG, "Found " + AD_HOSTS.size() + " ad hosts.");
            }
            catch(IOException e) {
                Log.e("AdBlocker", "Unable to load ad domains from file.");
            }

            return null;
        }
    }
}
