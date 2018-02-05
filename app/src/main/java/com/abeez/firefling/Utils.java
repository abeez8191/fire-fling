package com.abeez.firefling;

import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class Utils {

    private static final String fileEndings = ".webm .mpd .m3u8 .mkv .flv .vob .ogv .ogg .drc .avi .mov .qt .wmv .yuv .rm .rmvb .asf .amv .mp4 .m4p .m4v .mpg .mp2 .mpeg .mpe .mpv .m2v .m4v .svi .3gp .3g2 .mxf .roq .nsv";
    private static final List<String> fileExtensions = new ArrayList<>();
    private static final String TAG = "Utils";

    static {
        fileExtensions.addAll(Arrays.asList(fileEndings.split(" ")));
    }

    static URI convertStringToUri(final String potentialUrl) {
        return convertStringToUri(potentialUrl, false);

    }
    static URI convertStringToUri(final String potentialUrl, final boolean allowSearch) {
        if( potentialUrl == null ) {
            return null;
        }

        if( potentialUrl.startsWith("http") ) {
            return convertToUri(potentialUrl);
        }

        if( potentialUrl.contains(".") && ! potentialUrl.contains(" ") ) {
            return convertToUri("http://" + potentialUrl);
        }

        if( allowSearch ) {
            try {
                String encodedQuery = URLEncoder.encode(potentialUrl, StandardCharsets.UTF_8.toString());
                return convertToUri("https://www.google.com/search?q=" + encodedQuery);
            } catch (UnsupportedEncodingException ignore) { ; }
        }

        return null;
    }

    private static URI convertToUri(final String url) {
        try {
            return new URI(url);
        }
        catch(URISyntaxException e) {
            Log.e(TAG, "Exception while converting string to URI.", e);
            return null;
        }
    }

    static boolean isVideoLink(final URI uri) {
        if( uri == null ) {
            return false;
        }

        String path = uri.getPath();
        if( path != null ) {
            for( String extension : fileExtensions ) {
                if( path.endsWith(extension) ) {
                    return true;
                }
            }
        }

        return false;
    }
}
