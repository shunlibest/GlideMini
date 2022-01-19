package com.example.glidemini.load.engine;

import com.example.glidemini.cache.key.Key;

interface EngineJobListener {

  void onEngineJobComplete(EngineJob<?> engineJob, Key key, EngineResource<?> resource);

  void onEngineJobCancelled(EngineJob<?> engineJob, Key key);
}
