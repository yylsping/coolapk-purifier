package io.github.yylsping.coolapkpurifier;

/**
 * Structured outcome of one pinned business-hook install attempt. Lets the
 * coordinator and tests distinguish "feature off at startup" from a genuine
 * contract/version/install failure without scraping logs.
 */
enum InstallResult {
    INSTALLED,
    DISABLED,
    UNSUPPORTED_VERSION,
    TARGET_MISSING,
    CONTRACT_MISMATCH,
    INSTALL_FAILED,
    ALREADY_INSTALLED
}
