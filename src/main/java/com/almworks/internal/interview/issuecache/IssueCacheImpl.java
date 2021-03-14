package com.almworks.internal.interview.issuecache;

import org.slf4j.Logger;

import java.util.*;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.slf4j.LoggerFactory.getLogger;

public class IssueCacheImpl implements IssueCache {

  private static final Logger LOG = getLogger(IssueCacheImpl.class);

  private final IssueChangeTracker tracker;
  private final IssueLoader loader;
  private final Set<String> fieldIds;
  private final Map<Long, List<Listener>> issueIdsToListeners = new HashMap<>();
  private final Map<Long, Map<String, Object>> issueIdsToFieldValues = new HashMap<>();

  public IssueCacheImpl(IssueChangeTracker tracker, IssueLoader loader, Set<String> fieldIds) {
    this.tracker = tracker;
    this.loader = loader;
    this.fieldIds = Set.copyOf(fieldIds); // Converts to an immutable Set

    subscribeToTrackerChanges();
  }

  private void subscribeToTrackerChanges() {
    tracker.subscribe(issueIds -> {
      LOG.debug("Handle changes of issueIds {} from tracker", issueIds);
      loadIssues(issueIdsToListeners.keySet().stream()
          .filter(issueIds::contains)
          .collect(toSet()));
    });
  }

  private void loadIssues(Set<Long> issueIds) {
    if (!issueIds.isEmpty()) {
      LOG.debug("Call loader for issueIds {}", issueIds);
      loader.load(issueIds, fieldIds)
          .thenAccept(result -> result.getIssueIds()
              .forEach(issueId -> handleIssueChanges(issueId, result.getValues(issueId))));
    }
  }

  private void handleIssueChanges(Long issueId, Map<String, Object> newFieldValues) {
    Map<String, Object> oldFieldValues = issueIdsToFieldValues.get(issueId);
    Map<String, Object> changedFieldValues = oldFieldValues == null
        ? newFieldValues
        : newFieldValues.entrySet().stream() // Skip null check due to the comment above IssueLoader.load
            .filter(entry -> {
              Object oldValue = oldFieldValues.get(entry.getKey());
              Object newValue = entry.getValue();

              // Skip null checks assuming that IssueLoader.getValues never returns a Map with null values
              // since otherwise it must be fixed in the loader as a design error
              return !oldValue.equals(newValue);
            })
            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));

    if (changedFieldValues.size() > 0) {
      ofNullable(issueIdsToListeners.get(issueId))
          .ifPresent(listeners -> {
            LOG.debug("Add a new or changed issue with id {} and values {} into the cache", issueId, changedFieldValues);
            listeners.forEach(listener -> listener.onIssueChanged(issueId, changedFieldValues));
            issueIdsToFieldValues.put(issueId, newFieldValues);
          });
    }
  }

  @Override
  public void subscribe(Set<Long> issueIds, Listener listener) {
    LOG.debug("Add a new listener of issueIds {}", issueIds);
    Set<Long> missingIssueIds = new HashSet<>();

    issueIds.forEach(issueId -> {
      List<Listener> issueListeners = issueIdsToListeners.get(issueId);

      if (issueListeners == null) {
        issueIdsToListeners.put(issueId, newArrayList(listener));
      } else {
        issueListeners.add(listener);
      }

      if (issueIdsToFieldValues.containsKey(issueId)) {
        listener.onIssueChanged(issueId, issueIdsToFieldValues.get(issueId));
      } else {
        missingIssueIds.add(issueId);
      }
    });

    loadIssues(missingIssueIds);
  }

  @Override
  public void unsubscribe(Listener listener) {
    List<Long> issueIdsWithoutListeners = new ArrayList<>();

    issueIdsToListeners.forEach((issueId, listeners) -> {
      if (listeners.contains(listener)) {
        if (listeners.size() > 1) {
          listeners.remove(listener);
        } else {
          issueIdsWithoutListeners.add(issueId);
        }
      }
    });

    issueIdsWithoutListeners.forEach(issueId -> {
      LOG.debug("The last listener has unsubscribed from issueId {}. Removing from the cache", issueId);
      issueIdsToListeners.remove(issueId);
      issueIdsToFieldValues.remove(issueId);
    });
  }

  @Override
  public Object getField(long issueId, String fieldId) {
    if (fieldId == null || !fieldIds.contains(fieldId)) {
      LOG.warn("FieldId {} is not stored in the cache", fieldId);
      return null;
    }

    return ofNullable(issueIdsToFieldValues.get(issueId))
        .map(issue -> issue.get(fieldId))
        .orElse(null);
  }

  @Override
  public Set<String> getFieldIds() {
    return fieldIds;
  }
}
