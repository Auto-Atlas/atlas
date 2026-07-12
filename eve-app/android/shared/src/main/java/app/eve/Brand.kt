package app.eve

/**
 * Single source for the assistant's user-facing name across phone and watch.
 * Keep in sync with app_name in app/ and wear/ strings.xml — manifests can only
 * reference a resource, so the launcher label lives there.
 */
const val ASSISTANT_NAME = "Atlas"
