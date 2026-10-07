package io.github.sudoitir.artemisstudio.platform.broker;

/**
 * What one account's attempt on one node came to. The two accounts are reported separately, so a
 * rejected Core account never hides an accepted management account, or the reverse.
 */
public enum AccountResult {
    /** The broker let the account in. */
    ACCEPTED,
    /** The broker answered and refused the account's credentials. */
    REJECTED,
    /** Nothing answered, so nothing is known of the account. */
    UNREACHABLE,
    /** No attempt was made: the node has no address to try, or does not take this kind of connection. */
    NOT_TRIED
}
