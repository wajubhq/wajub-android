# Consumer ProGuard/R8 rules for wajub-mobile-compose.
#
# No reflection-sensitive APIs are used directly by this module (no JSON model
# classes, no annotation-processed code). The stripe-android and Jetpack
# Compose dependencies ship their own consumer rules bundled in their AARs,
# which R8 picks up automatically, so no rules need to be duplicated here.
