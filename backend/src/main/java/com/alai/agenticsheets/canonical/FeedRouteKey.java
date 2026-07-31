package com.alai.agenticsheets.canonical;

/** (clientId, feedType) -- the key Step 9's inbox scanner resolves a
  * parsed filename into, looking up {@link RegistrySnapshot#routes()}. */
public record FeedRouteKey(String clientId, String feedType) {
}
