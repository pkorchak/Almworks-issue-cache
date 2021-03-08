package com.almworks.internal.interview.issuecache;

import java.util.Set;

public class IssueCacheImpl implements IssueCache {
  public IssueCacheImpl(IssueChangeTracker tracker, IssueLoader loader, Set<String> fieldIds) {

  }

  @Override
  public void subscribe(Set<Long> issueIds, Listener listener) {

  }

  @Override
  public void unsubscribe(Listener listener) {

  }

  @Override
  public Object getField(long issueId, String fieldId) {
    return null;
  }

  @Override
  public Set<String> getFieldIds() {
    return null;
  }
}
