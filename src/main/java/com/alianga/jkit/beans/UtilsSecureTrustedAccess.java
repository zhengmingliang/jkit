package com.alianga.jkit.beans;

import com.alianga.jkit.reflect.SecureTrustedAccess;

/**
 * Internal trusted access token for beans package.
 * Extends {@link SecureTrustedAccess} to enable privileged
 * getter/setter invocation via the reflect module.
 */
final class UtilsSecureTrustedAccess extends SecureTrustedAccess {
}
