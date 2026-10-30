package com.alianga.jkit.storage.webdav.versioned;

import java.util.Objects;

/**
 * WebDAV 连接端点（库侧配置，不依赖 UI / Preferences）。
 *
 * @author 郑明亮
 */
public final class WebDavEndpoint {
    private final String serverUrl;
    private final String username;
    private final String password;
    private final String remoteDirectory;

    public WebDavEndpoint(String serverUrl, String username, String password, String remoteDirectory) {
        this.serverUrl = serverUrl == null ? "" : serverUrl.trim();
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.remoteDirectory = remoteDirectory == null ? "" : remoteDirectory.trim();
    }

    public String serverUrl() {
        return serverUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String remoteDirectory() {
        return remoteDirectory;
    }

    public WebDavEndpoint withRemoteDirectory(String dir) {
        return new WebDavEndpoint(serverUrl, username, password, dir);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WebDavEndpoint)) {
            return false;
        }
        WebDavEndpoint that = (WebDavEndpoint) o;
        return Objects.equals(serverUrl, that.serverUrl)
                && Objects.equals(username, that.username)
                && Objects.equals(password, that.password)
                && Objects.equals(remoteDirectory, that.remoteDirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverUrl, username, password, remoteDirectory);
    }
}
