package com.cafecli.browser;

public interface BrowserConnector {
    String status();

    String connectDefault();

    String disconnect();
}
