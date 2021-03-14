package com.almworks.internal.interview.issuecache;

import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

/**
 * You are expected to write unit tests for Issue Cache.
 */
public class IssueCacheTest {

  private final static Set<String> FIELD_IDS = Set.of("key", "summary");

  private final static Map<String, Object> ISSUE_1 = Map.of("key", "k1", "summary", "s1");
  private final static Map<String, Object> ISSUE_1_V2 = Map.of("key", "k11", "summary", "s11");
  private final static Map<String, Object> ISSUE_2 = Map.of("key", "k2", "summary", "s2");
  private final static Map<String, Object> ISSUE_2_V2 = Map.of("key", "k21", "summary", "s21");
  private final static Map<String, Object> ISSUE_3 = Map.of("key", "k3", "summary", "s3");
  private final static Map<String, Object> ISSUE_4 = Map.of("key", "k4", "summary", "s4");

  private final IssueCache.Listener listener1 = mock(IssueCache.Listener.class);
  private final IssueCache.Listener listener2 = mock(IssueCache.Listener.class);

  private final MockIssueChangeTracker myTracker = new MockIssueChangeTracker();
  private final MockIssueLoader myLoader = new MockIssueLoader();
  private final IssueCache myCache = new IssueCacheImpl(myTracker, myLoader, FIELD_IDS);

  @Before
  public void setupSubscriptionTo12() {
    checkCacheDoesNotContain(1L, 2L, 3L, 4L);
    assertThat(myCache.getField(1L, "key"), nullValue());

    myCache.subscribe(Set.of(1L, 2L), listener1);

    assertThat(myLoader.requests, hasSize(1));
    checkCacheDoesNotContain(1L, 2L, 3L, 4L);

    myLoader.reply(Map.of(1L, ISSUE_1, 2L, ISSUE_2));

    verify(listener1).onIssueChanged(1L, ISSUE_1);
    verify(listener1).onIssueChanged(2L, ISSUE_2);
    verifyNoMoreInteractions(listener1);
    verifyZeroInteractions(listener2);
    reset(listener1);

    // TODO Consider usage of PowerMock to simplify cache state validation
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2));
    checkCacheDoesNotContain(3L, 4L);
  }

  @Test
  public void subscribe_LoadsAllSpecifiedIssuesIfNotStoredInCache() {
    myCache.subscribe(Set.of(3L, 4L), listener2);

    assertThat(myLoader.requests, hasSize(1));
    checkRequests(Set.of(3L, 4L));

    myLoader.reply(Map.of(3L, ISSUE_3, 4L, ISSUE_4));

    verify(listener2).onIssueChanged(3L, ISSUE_3);
    verify(listener2).onIssueChanged(4L, ISSUE_4);
    verifyNoMoreInteractions(listener2);
    verifyZeroInteractions(listener1);
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2, 3L, ISSUE_3, 4L, ISSUE_4));
  }

  @Test
  public void subscribe_LoadsOnlyMissingIssuesIfSomeStoredInCache() {
    myCache.subscribe(Set.of(2L, 3L), listener2);

    assertThat(myLoader.requests, hasSize(1));
    checkRequests(Set.of(3L));

    myLoader.reply(Map.of(3L, ISSUE_3));

    verify(listener2).onIssueChanged(2L, ISSUE_2);
    verify(listener2).onIssueChanged(3L, ISSUE_3);
    verifyNoMoreInteractions(listener2);
    verifyZeroInteractions(listener1);
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2, 3L, ISSUE_3));
  }

  @Test
  public void subscribe_DoesNotLoadIssuesIfAllStoredInCache() {
    myCache.subscribe(Set.of(1L, 2L), listener2);

    assertThat(myLoader.requests, hasSize(0));

    verify(listener2).onIssueChanged(1L, ISSUE_1);
    verify(listener2).onIssueChanged(2L, ISSUE_2);
    verifyNoMoreInteractions(listener2);
    verifyZeroInteractions(listener1);
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2));
  }

  @Test
  public void unsubscribe_TheLastListenerOfIssueRemovesItFromCache() {
    myCache.subscribe(Set.of(1L), listener2);
    verify(listener2).onIssueChanged(1L, ISSUE_1);

    myCache.unsubscribe(listener1);

    checkCacheContains(Map.of(1L, ISSUE_1));
    checkCacheDoesNotContain(2L);

    myTracker.recordChanges(Set.of(1L));
    myLoader.reply(Map.of(1L, ISSUE_1_V2));

    verify(listener2).onIssueChanged(1L, ISSUE_1_V2);
    verifyNoMoreInteractions(listener1, listener2);
    checkCacheContains(Map.of(1L, ISSUE_1_V2));

    myCache.unsubscribe(listener2);

    checkCacheDoesNotContain(1L, 2L);
  }

  @Test
  public void unsubscribe_DoesNotPutIssueInCacheIfUnsubscribedDuringLoading() {
    myCache.subscribe(Set.of(3L), listener2);

    assertThat(myLoader.requests, hasSize(1));
    checkRequests(Set.of(3L));
    checkCacheDoesNotContain(3L);

    myCache.unsubscribe(listener2);
    myLoader.reply(Map.of(3L, ISSUE_3));

    verifyZeroInteractions(listener1, listener2);
    checkCacheDoesNotContain(3L);
  }

  @Test
  public void trackerChange_LoadsListenedIssues() {
    myCache.subscribe(Set.of(1L), listener2);
    verify(listener2).onIssueChanged(1L, ISSUE_1);

    myTracker.recordChanges(Set.of(2L));

    assertThat(myLoader.requests, hasSize(1));
    checkRequests(List.of(2L));
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2));

    myLoader.reply(Map.of(2L, ISSUE_2_V2));

    verify(listener1).onIssueChanged(2L, ISSUE_2_V2);
    verifyNoMoreInteractions(listener1, listener2);
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2_V2));

    myTracker.recordChanges(Set.of(1L, 2L));

    assertThat(myLoader.requests, hasSize(1));
    checkRequests(Set.of(1L, 2L));
    checkCacheContains(Map.of(1L, ISSUE_1, 2L, ISSUE_2_V2));

    myLoader.reply(Map.of(1L, ISSUE_1_V2, 2L, ISSUE_2));

    verify(listener1).onIssueChanged(1L, ISSUE_1_V2);
    verify(listener2).onIssueChanged(1L, ISSUE_1_V2);
    verify(listener1).onIssueChanged(2L, ISSUE_2);
    verifyNoMoreInteractions(listener1, listener2);
    checkCacheContains(Map.of(1L, ISSUE_1_V2, 2L, ISSUE_2));
  }

  @Test
  public void trackerChange_DoesNotLoadNotListenedIssues() {
    myTracker.recordChanges(Set.of(3L, 4L));

    assertThat(myLoader.requests, hasSize(0));
    verifyZeroInteractions(listener1, listener2);
  }

  @Test
  public void trackerChange_DoesNotCallListenersIfNoChangesInCachedFields() {
    myTracker.recordChanges(Set.of(1L, 2L));

    assertThat(myLoader.requests, hasSize(1));
    checkRequests(Set.of(1L, 2L));

    myLoader.reply(Map.of(1L, ISSUE_1, 2L, ISSUE_2));

    verifyZeroInteractions(listener1, listener2);
  }

  @Test
  public void getField_ReturnsNullWhenFieldIdIsNull() {
    assertThat(myCache.getField(1L, null), nullValue());
  }

  @Test
  public void getField_ReturnsNullWhenFieldIdIsUnknown() {
    assertThat(myCache.getField(1L, "unknownId"), nullValue());
  }

  @Test
  public void getFieldIds_Success() {
    assertThat(myCache.getFieldIds(), is(FIELD_IDS));
  }

  @SafeVarargs
  private void checkRequests(Collection<Long>... requests) {
    assertThat(myLoader.requests, contains(
        Arrays.stream(requests).map(requestedIssueIds ->
            hasProperty("issueIds", containsInAnyOrder(requestedIssueIds.toArray()))
        ).collect(toList())
    ));
  }

  private void checkCacheContains(Map<Long, Map<String, Object>> issueIdsToFieldValues) {
    issueIdsToFieldValues.forEach((issueId, fieldValues) ->
        fieldValues.forEach((fieldId, fieldValue) -> assertThat(myCache.getField(issueId, fieldId), is(fieldValue))));
  }

  private void checkCacheDoesNotContain(Long... issueIds) {
    Arrays.stream(issueIds).forEach(issueId ->
        FIELD_IDS.forEach(fieldId -> assertThat(myCache.getField(issueId, fieldId), nullValue())));
  }

  private static class MockIssueChangeTracker implements IssueChangeTracker {
    private IssueChangeTracker.Listener myListener;

    @Override
    public void subscribe(Listener listener) {
      assertThat("unexpected second call to subscribe()", myListener, nullValue());
      myListener = listener;
    }

    public void recordChanges(Set<Long> changedIds) {
      myListener.onIssuesChanged(changedIds);
    }

    @Override
    public void unsubscribe(Listener listener) {
      fail("Unexpected call to unsubscribe()");
    }
  }


  private static class MockIssueLoader implements IssueLoader {
    public List<IssueLoadRequest> requests = new ArrayList<>();

    @Override
    public CompletionStage<LoadResult> load(Collection<Long> issueIds, Set<String> fieldIds) {
      IssueLoadRequest request = new IssueLoadRequest(issueIds, fieldIds);
      requests.add(request);
      request.resultFuture.handle((r, t) -> requests.remove(request));
      return request.resultFuture;
    }

    public void reply(Map<Long, Map<String, Object>> result) {
      assertThat(requests, not(empty()));
      requests.get(0).resolve(result);
    }
  }


  public static class IssueLoadRequest {
    public final List<Long> issueIds;
    public final Set<String> fieldIds;
    public final CompletableFuture<LoadResult> resultFuture = new CompletableFuture<>();

    public IssueLoadRequest(Collection<Long> issueIds, Set<String> fieldIds) {
      this.issueIds = issueIds.stream().sorted().collect(toList());
      this.fieldIds = fieldIds;
    }

    public Collection<Long> getIssueIds() {
      return issueIds;
    }

    public Set<String> getFieldIds() {
      return fieldIds;
    }

    public CompletableFuture<LoadResult> getResultFuture() {
      return resultFuture;
    }

    public void resolve(Map<Long, Map<String, Object>> result) {
      assertThat(issueIds, containsInAnyOrder(result.keySet().toArray(Long[]::new)));
      resultFuture.complete(new LoadResult(this, result));
    }
  }


  private static class LoadResult implements IssueLoader.Result {
    private final IssueLoadRequest myRequest;
    private final Map<Long, Map<String, Object>> myResult;

    private LoadResult(IssueLoadRequest req, Map<Long, Map<String, Object>> result) {
      myRequest = req;
      myResult = result;
    }

    @Override
    public Map<String, Object> getValues(long issueId) {
      return myResult.get(issueId);
    }

    @Override
    public Set<Long> getIssueIds() {
      return myResult.keySet();
    }

    @Override
    public Set<String> getFieldIds() {
      return myRequest.fieldIds;
    }
  }
}