package com.abeez.firefling;

import com.buesing.aaron.fire.fling.AdBlockPlus;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
public class ExampleUnitTest {
    @Test
    public void addition_isCorrect() throws Exception {
        assertEquals(4, 2 + 2);
    }

    @Test
    public void AdBlockPlusBlocksWildCard() {
        String test = "/banner/*/img^";
        Object[][] tests = new Object[][] {
                {"http://example.com/banner/foo/img", true},
                {"http://example.com/banner/foo/bar/img?param", true},
                {"http://example.com/banner//img/foo", true},
                {"http://example.com/banner/img", false},
                {"http://example.com/banner/foo/imgraph", false},
                {"http://example.com/banner/foo/img.gif", false}
        };

        AdBlockPlus adBlockPlus = new AdBlockPlus(test);

        for(Object[] example : tests) {
            String url = (String) example[0];
            boolean result = (Boolean) example[1];
            String message = String.format("URL (%s) %s be an ad.", url,
                    (result) ? "should" : "should not");

            assertEquals(message, result, adBlockPlus.isAd(url));
        }
    }

    @Test
    public void AdBlockPlusBlocksDomainNameAnchor() {
        String test = "||ads.example.com^";

        Object[][] tests = new Object[][] {
                {"http://ads.example.com/foo.gif", true},
                {"http://server1.ads.example.com/foo.gif", true},
                {"https://ads.example.com:8000/", true},
                {"http://ads.example.com.ua/foo.gif", false},
                {"http://example.com/redirect/http://ads.example.com/", false}
        };

        AdBlockPlus adBlockPlus = new AdBlockPlus(test);
        for(Object[] example : tests) {
            String url = (String) example[0];
            boolean result = (Boolean) example[1];
            String message = String.format("URL (%s) %s be an ad.", url,
                    (result) ? "should" : "should not");

            assertEquals(message, result, adBlockPlus.isAd(url));
        }
    }

    @Test
    public void AdBlockPlusBlocksExactRule() {
        String test = "|http://example.com/|";
        Object[][] tests = new Object[][] {
                {"http://example.com/", true},
                {"http://example.com/foo.gif", false},
                {"http://example.info/redirect/http://example.com/", false}
        };

        AdBlockPlus adBlockPlus = new AdBlockPlus(test);
        for(Object[] example : tests) {
            String url = (String) example[0];
            boolean result = (Boolean) example[1];
            String message = String.format("URL (%s) %s be an ad.", url,
                    (result) ? "should" : "should not");

            assertEquals(message, result, adBlockPlus.isAd(url));
        }
    }

    @Test
    public void AdBlockPlusExceptionList() {
        String test = "/ad/*/images\n@@||ads.example.com^";
        Object[][] tests = new Object[][]{
                {"http://test.example.com/ad/something/here/images/123.gif", true},
                {"http://a.ads.example.com/ad/something/here/images/123.gif", false}
        };

        AdBlockPlus adBlockPlus = new AdBlockPlus(test);
        for(Object[] example : tests) {
            String url = (String) example[0];
            boolean result = (Boolean) example[1];
            String message = String.format("URL (%s) %s be an ad.", url,
                    (result) ? "should" : "should not");

            assertEquals(message, result, adBlockPlus.isAd(url));
        }
    }
}