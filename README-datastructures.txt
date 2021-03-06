TODO: changes needed:
---------------------

Daily rankings - want to show history of total and 3-day rankings over the last week.

Dissolve rate - when policies are replaced/retired: refund with the usual penalty rate for the policy owner, 100% refund for other people who voted on it.
  - For those deleted due to abuse, refund everything.
  - Can we do this by writing a user_policy_votes record for the owner with appropriate penalty? Note we have to handle the fact that the policy has been replaced/disabled but still include the penalty in the count.

Only owner (or admin) can retire policy.

Users can flag policies for retire-in-favour-of-other.

Owner should be able to indicate "I recommend voting on this other policy instead" when they elect to retire their policy.

Hot tag / category rankings: N-day rankings same as for policies (though separate number-of-days config just in case)

Tag rankings are best recalculated in the background, else it's difficult to keep everything in sync.

  - Whenever we recalc votes for a policy, trigger recalc for each tag currently on that policy.
    - set needs_votes_recalc
  - Whenever we add or remove tags from a policy, trigger recalc for each tag involved.
    - set needs_votes_recalc
  - Background process (a few minutes) does full-cluster query on the indexed needs_votes_recalc column,
    and for each one:
    - Read list of policies
    - Read and add up all their vote totals
    - Insert tags.total_votes
    - Insert into tag_ranking_current
    TODO


tag_ranking_current[<int>"-day"] {
    <date>_<tagID> ...: <votecount>:short_name, with TTL set to expire at end of Nth day
}

- Figure out how/when we update policy_ranking_history.



Background on Cassandra data model:
-----------------------------------
Cassandra's structure is "column families" - tables with rows looked up by a unique key, with each row containing any number of "columns", which are name->value pairs storing a single value - i.e. each row is a Map<ColumnName, Value>. Very large numbers of columns per row (e.g. millions) are OK.

Columns in a row are stored in sorted order. Writes insert or delete one or more columns from a row. Reads retrieve single columns from a row, or ordered ranges of columns.

Writes to a single row are very fast. Reads from a single row are a little slower but usually only milliseconds.

Reads across all rows are slow (table scan across whole cluster!) and return results in random order - best avoided except for background processes or unless the number of rows is small.

There is also a "super column family" structure: values in the row are "super columns" instead of "columns", and each one holds multiple name->value pairs (subcolumns) instead of a single value: i.e. each row is a Map<SuperColumnName, <Map<SubColumnName, Value>>>. These should be kept smaller - large numbers of supercolumns are OK, but small numbers of subcolumns for each.


Background on Cassandra consistency behavior:
---------------------------------------------
Cassandra is a timestamp-based eventual-consistency system with no check-and-write operations, so some care is needed for accurate vote counting, and to prevent loopholes such as a user assigning their votes in parallel from different browser sessions, and problems such as delayed writes (e.g. due to a node getting a write backlog during compaction) making counts inaccurate.

There is no locking, no transactions: reads across multiple rows aren't isolated from concurrent writes, and writes to multiple rows are independent and could fail part-way through. Also, as I understand it, multi-column writes are atomic within each row, but not transactionally isolated from concurrent reads of that row: i.e. when writing columns 1 and 2, a concurrent read may see the new column 1 but the old column 2. This needs care in some cases - can't have dependent data in separate columns if it's written more than once and must be atomically updated.

Cassandra's writes use a timestamp mechanism; the "timestamp" for each write to each column is an arbitrary client-supplied 64-bit integer, and when there is a write to an existing column, the write is applied only if the timestamp is greater than that of the column's old value. For example, if you set timestamps to be client current time, then newest write eventually wins; if you set (0 - client current time), oldest write eventually wins; or you can use some other app-defined integer value. This resolution process happens separately for each column in an update. It applies for deletes too. There's no distinction between "create" and "update" writes: "delete" followed by "write" will re-create the deleted column if the write timestamp is newer.

Consistency levels: it's tunable; when we have only one node it's not an issue, but once we have three or more, we'll configure for strong consistency, because it removes many of the issues with stale data. We'll use:
  - Replication Factor 3 (or more)
  - Write Consistency: quorum (2, if 3 nodes)
  - Read Consistency quorum (2, if 3 nodes)
This means a successful write followed by a successful read will always see the data it's just written (or newer data, if someone else is also writing) - this design assumes this constraint. We'll still have some failure tolerance - we can operate with any one node (for a given key's replica set) down or unresponsive, but reads will fail if two are down. (Writes will also fail with two nodes down in some conditions, depending on number of nodes in cluster - hinted handoff may allow it to keep working if there are four nodes total hence still two active.)
Note however that if the write fails, then the new data may still have been written; it might not yet be readable, but it may later reappear and replace the old data, despite the write failing. See "Eventual consistency of lost writes" below.

Indexing: Cassandra's secondary indexes are not intended for high-uniqueness indexed values (e.g. username -> userID), but for aggregation queries where there are many rows with the same value; queries against a secondary index have to contact ALL nodes in the cluster, as I understand it. So where we have fields with high uniqueness that we want to index, we make our own lookup column families - this then only has to contact READ_CONSISTENCY nodes.

The nasty corner cases we have to handle:
  - No locking. There's no atomic check-and-write operation, no lock-for-update, and no single point of consistency. This means there's no safe way to do insert-if-not-present kinds of operations. The best we can do is to do a best-effort check, then insert anyway and resolve conflicts later.
  - Retrying failed writes. A write command can fail due to server load / timeout, or because a server crashes while being written to. For some cases there is no way for the client to tell if the value was nevertheless persisted. This means we need most of our writes to be safely retryable (idempotent).
  - Client death in the middle of a write that spans multiple rows / column families. The separate parts are not atomic, so we need mechanisms in place to ensure the missing writes get performed if the webapp user thread fails in the middle, or the first one succeeds but the second one times out. This is done with "staging" fields, such that if we see them, we retry the write to the other location until done; and with background cleanup processes. Fortunately there are only a few of these cases. Example: user_policy_votes.pending_votes_ used as "staging" for things that must be written to both user_policy_votes and policy_new_votes.
  - Eventual consistency of lost writes. In some rare cases - if some of the nodes fail while we're writing, but are later recovered - then a write could appear to the client to fail, and the old data may later still be readable, but when the written nodes eventually recover, the "lost" newer-timestamped data gets propagated and wins. This could take a long time (we have up to Cassandra GC_GRACE_SECONDS to recover the failed node; beyond that we have to clean and repopulate it), so we have to code for this. Example: use of vote chaining for conflict resolution in user_policy_votes / policy_new_votes; when the late writes appear they'll change the chaining and the next read (or background process) will update totals accordingly.

Cassandra column families:
--------------------------
Pseudocode. '...' means a variable series of columns with names varying by date or id.

Re. use of super column families: for user_policy_votes and policy_new_votes, we can either use supercolumns or a concatenated value in a single column; it's important these updates be atomic, so the latter may be necessary for that: need to check whether supercolumn timestamps and write visibility apply to the individual subcolumns.

Main keyspace with replication factor 3, all writes AND reads with consistency level 2:

users: (key = <userID> : timeUUID) {
    username: text
    email: text
    password_hash: binary
    first_name: text
    last_name: text
    publish_real_name: boolean
    publish_votes: boolean
    vote_salary_last_paid_timestamp: time
    last_login: <time>
    last_active: <time>
    maybe other user data as needed - password reset mechanism, etc.
}

user_vote_salary: (key = <userID>) {
    <date> ...: int (never changed once written)
}

user_roles: (key = <userID>) {
    <roleID> : [nothing]
}

user_policy_watchlist: (key = <userID>) {
    <policyID>: [nothing]
}

user_access_history: (key = <userID>) {
    <timestamp> ... : {
        ip_address
        cookie
    }
}

user_ip_addresses: (key = <userID) {
    <ipaddr> ... : last seen date
}

user_cookies: (key = <userID) {
    <cookie> ... : last seen date
}

user_comments: (key = <userID) {
    <commentID> ... : nothing
}

user_messages: (key = <userID>) {
    <timeUUID> ... : {
        message_type: <messageTypeID>
        other named fields as appropriate, e.g. policyID, ...
    }
}

cookies: (key = cookie) {
    first_seen: time
    last_seen: time
    [comment_hash: <random hash value>]
}

cookie_access_history: (key = cookie) {
    <timestamp> ... : {
        ip_address
        user
    }
}

cookie_ip_addresses: (key = cookie) {
    ip_addr ... : last-seen
}

cookie_users: (key = cookie) {
    <userID> ... : last-seen
}

cookie_comments: (key = cookie) {
    <commentID> ... : nothing
}

ip_addresses: (key = ipaddress) {
    first_seen: time
    last_seen: time
    [comment_hash: <randomly generated string>]
}

ip_access_history: (key = ipaddress) {
    <timestamp> ... : {
        cookie
        userid
    }
}

ip_cookies: (key = ipaddress) {
    cookie ... : last-seen
}

ip_users: (key = ipaddress) {
    userID ... : last-seen
}

ip_comments: (key = ipaddress) {
    <commentID> ... : nothing
}

categories: (key = <categoryID> : timeUUID) {
    short_name: text
    description: text
}

category_policies: (key = <categoryID> : timeUUID) {
    <policyID> ... : policy name (TTL some multiple of the cycle time of the update-policies background process)
}

tags: (key = <tagID> : timeUUID) {
    name: text
    edited_by: <userID>
    needs_votes_recalc: boolean (secondary indexed)
    total_votes: int
    last_changed: time
    last_used: time
    [deleted: boolean]
}

tag_policies: (key = <tagID>) {
    <policyID> ... : policy name (TTL some multiple of the cycle time of the update-policies background process)
}

policies: (key = <policyID> : timeUUID) {
    state: "active" or "replaced" or "deleted" or "pending-deletion" or "retired"
    state_changed: <time>
    short_name: text
    description: text
    rationale: text
    costs_to_taxpayers: text
    who_affected: text
    how_affected: text
    is_party_official: boolean
    category: <categoryID>
    party: <partyID>
    owner: <userID>
    last_edit_date: <time>
    total_votes: int
    finalized_votes: int:timeUUID
    replaced_by: <policyID>
}

policy_links: (key = <policyID>) {
    <timeUUID>: text
}

policy_tags: (key = <policyID>) {
    <tagID> ...: <userID>
}

policy_replaces: (key = <policyID>) {
    <policyID> ...: nothing (or timestamp maybe, or userID)
}

user_policy_votes_pending: (key = <userID>) {
    version: <voteRecordID> ... :
        JSON : { // Due to consistency requirements, and variable subcolumn list: atomic record updates with single timestamp
            parent: <voteRecordID>
            votes: {
                <policyID> ... : {
                    increment: int
                    penaltyincrement: int
                    newtotal: int
                    penaltytotal: int
                }
            }
            [policy_created: <policyID>]
        }
        cassandra timestamp = time of submitting vote, so newest wins
}

user_policy_votes: (key = <userID> : timeUUID) {
    version: <voteRecordID> ... :
        JSON : { // Due to consistency requirements, and variable subcolumn list: atomic record updates with single timestamp
            parent: <voteRecordID>
            votes: {
                <policyID> ... : {
                    increment: int
                    penaltyincrement: int
                    newtotal: int
                    penaltytotal: int
                }
            }
            [policy_created: <policyID>]
        }
        cassandra timestamp copied from pending_votes.
}

policy_new_votes: (key = <policyID> : timeUUID) {
    parent: <voteRecordID> ... :
        JSON : { // Due to consistency requirements: atomic record updates with single timestamp
            vote_increment: int (can be -/0/+)
            user_id: <userID>
            version: <voteRecordID>
        }
        cassandra timestamp: from user_policy_votes.pending_votes
}

policy_vote_history: (key = <policyID>_<date>) {
    <voteRecordID> ...: {
        user_id: <userID>
        vote_increment: int (can be -/+, never 0)
        new_vote_total: int
    }
}

policy_vote_daily_change: (key = <date>) {
    <policyID> ...: int
}

policy_ranking_current["total-votes"] {
    <votecount>_<policyID> ...: short_name
}

policy_ranking_current[<int>"-day"] {
    <date>_<policyID> ...: <votecount>:short_name, with TTL set to expire at end of Nth day
}

policy_ranking_history: (key = hour (or minute, or whatever)) {
    <votecount>_<policyID> ...: short_name
}

parties: (key = <partyID> : timeUUID) {
    active: boolean
    short_name: text
    description: text
}

users_by_name: (key = <username> : text) {
    user_id: <userID>
    registered_timestamp: timeUUID
}

comments: (key = "category_" + <categoryID>|"policy_" + <policyID>) {
    <commentID> : timeUUID ...: { // ID is the creation time
        parent_id: <commentID>
        user_id: <userID>
          or:
          anonymous_user_id: <userID>
        ip_address: addr
        subject: text
        body: text
        edited: time
        edited_by: <userID> // If moderators can edit
        deleted_by: <userID>
        deleted_reason: text
    }
}

comment_thread_roots: (key = "category_" + <categoryID>|"policy_" + <policyID>) {
    <parentTimeUUID> ... : nothing // Top-level comment threads
}

// TODO: how do TimeUUIDs order if those from multiple nodes are sorted lexically?
//       Can't use the TimeUUID comparator here, have to compare by bytes or by UTF8,
//       but we still need things to be time-ordered.
comments_threaded: (key = "category_" + <categoryID>|"policy_" + <policyID>) {
    <parentTimeUUID>[:<childTimeUUID>][:<childTimeUUID>...] ...: nothing
}

moderation["policy"] {
    <timeUUID> : {
        policy_id: <policyID>
        reporting_user: <userID>
        reason: text
    }
}

moderation["comment"] {
    <timeUUID> : {
        comment_id: <commentID>, e.g. "policy_<policyID>:<commentID>"
        reporting_user: <userID>
        reason: text
    }
}

moderation["tag"] {
    <timeUUID> : {
        tag_id: <tagID>
        reporting_user: <userID>
        reason: text
    }
}

moderation["user"] {
    <timeUUID> : {
        user_id: <userID>
        reporting_user: <userID>
        reason: text
    }
}

moderation["login"] {
    <timeUUID> : {
        user_id: <userID> (if known)
        user_name: text
        reason: text
    }
}

memcache_string["vote-salary"]: {
    <LocalDate> : long
}

memcache_string["active-policies"]: {
    <policyName> ...: <policyID>
}

memcache_string["active-parties"]: {
    <partyName> ...: <partyID>
}

memcache_string["active-categories"]: {
    <categoryName> ...: <categoryID>
}

memcache_string["tags-by-name"]: {
    <tagName> ...: <tagID>
}

memcache_string["webapp-instances"]: {
    <ipaddr> ...: (nothing), TTL say 5 mins
}

memcache_string["voting config"]: {
    vote_finalize_delay_seconds: int
    ranking_window_days: int (3)
    user_vote_salary_frequency_days: int (7)
    user_vote_salary_increment: int (100)
    vote_cost_to_create_policy: int (100)
    vote_withdrawal_penalty_percentage: int (50)
}

memcache_string["email-config"] {
    default_email_frequency: "daily"
}

memcache_timeuuid["featured-policies"]: {
    <policyID> ... : (nothing)
}

memcache_timeuuid["message-types"]: {
    <messageID> ... : formatstring
}

misc_string["vote-history-dates"]: {
    <date> : (nothing)
}

misc_string["log hours"]: {
    <DateAndHour> : (nothing)
}


In a separate keyspace, so we can set different replication / consistency rules:

log: (key = <date>:<hour>) {
    <timeUUID> ... : {
        server_ip: <string>
        category: ERROR/INFO etc
        something... text/data
    }
}

performance: (key = <date>:<hour>) {
    <timeUUID> ... : {
        server_ip: <string>
        interval_length: millis
        servlet request count this interval
        cassandra query count this interval
        load average, jvm and system memory stats etc
    }
}


Explanation of data design and usage:
-------------------------------------
users:
  - Uniqueness and indexing of username is handled separately, see users_by_name below.
  - User vote salary is in separate timestamp-named columns, added to every week. These are never modified once written - i.e. we don't subtract when allocating votes, we just calculate the difference of (total salary - current policy vote allocations). Can either have a background process to add these columns weekly, or just update whenever we look at that user's record.
  - password_hash: bcrypt hash of password.
  - Roles: columns present to indicate role_admin, role_edit_policies, role_edit_users etc. Secondary indexes on these for user config screens.
  - Watchlist: add policyIDs as policy_watchlist_ columns.
  - List of comment IDs this user has created.
  - When user logs in, add the anonymous_users ID to users.anonymous_ list, and the userID to the anonymous_users.user_id_ list - useful for moderation.
  - Other user data as needed - last login etc.

user_messages:
  The system will add entries here to notify users of things, e.g. that their policy has been retired, etc. Shown at login and in user profile; users can delete entries when read. The columns are named inputs to String.format for the appropriate message text ID.

ipaddresses:
  When any user accesses the system:
    - a record is created here with first_seen if not present
    - the last_seen column is updated
    - an active_ date column is written
  The hash is shown with the comment if the user wasn't logged in. If they WERE logged in, probably don't show it - registered users gain the privilege of not disclosing who they share an IP with.

anonymous_users:
  When non-logged-in users access the system, they're given a persistent random cookie.
    - a record is created here with first_seen if not present
    - the last_seen column is updated
    - active_ date is written
  Users need not be registered or logged in to post comments. If these non-logged-in users post comments, the anonymous ID (from the cookie) is recorded instead of the userID; and the random strings from both the anonymous_users record and from the ipaddresses record are generated and stored if not used yet, then shown with all comments matching that record.

categories:
  Fixed set of items: Education, Tax, Health etc. Probably we will set these at startup, don't bother with UI for now.
  - policies_<policyID>: has limited TTL, inserted when assigning a category to a policy, and reinserted occasionally by a background process, so we don't need to worry about lost inserts or deletes when changing category for a policy.

tags:
  Tags that can be applied to policies, e.g. "capital gains tax". Policies can have any number of tags.
  We don't try very hard to prevent duplicate IDs for the same name (if people assign them at the same time) - we need to be able to merge tags anyway, so we'll just have a background process to merge duplicates.
  - policies_<policyID>: has limited TTL, inserted when assigning a tag to a policy, and reinserted occasionally by a background process, so we don't need to worry about lost inserts or deletes when adding/removing tags from policies.
  - Renaming tags: add new entry to memcache["tags-by-name"], then delete old entry, then update "tags".
  - Merging/deleting tags:
    - Loser tag gets its ID replaced or removed from all policy records, setting "deleted" and "last_used" fields on loser tag if we find any.
    - If there was nothing to replace:
      - If last_used is more than GC_GRACE_SECONDS ago, delete row from tags.
      - Else set last_used and leave record in tags for now.
    - Winner of merges is in memcache["tags-by-name"].

policies:
  Policy current config and calculated totals.
  - total_votes: cached count that resulted from the last policy_new_votes change. Any operation that changes policy_new_votes will then read back, calculate and write this column, with the write timestamp set to the time we started the read back. Also, we'll have a periodic background process (say, every few minutes) that recalculates it - last resort protection against failures in the recalc following any given update.
  - finalized_votes: This is the count of all the votes that have been "finalized" and copied to "policy_vote_history", i.e. they are old enough that we can be sure that all writes have arrived and conflicts are resolved. This provides the base value to which increments in policy_new_votes are added when recalculating policies.total_votes. The timeUUID is the "version" from the latest vote included in the count - in a single column because it's critical they're always updated atomically together.
  - state: normally "active" which means it's available for user votes. Other states: "deletion-pending", "deleted".
  - Deleting a policy (e.g. due co conflict at creation - see user_policy_votes; or for abusive stuff): just set the state to "pending-deletion". The background processes take care of the rest. Also try to delete from memcache["active-policies"] (makes immediately invisible) but ignore failure, but background process will tidy up if this write fails.
  - Set tags.last_used when assigned.

user_policy_votes:
  Each row is the user vote history for a single user across all policies. Change in votes and new total votes per policy for this user is in policyid-named columns in each record. The structure is designed to cope - in the absence of a locking mechanism - with multiple vote submits by the same user, by detecting conflicting writes and garbage-collecting any child updates whose parent writes lost the conflict resolution.
  - When their vote allocation is first accessed, new user accounts receive a single user_policy_votes entry with zero ID and zero vote allocations, to act as the root entry in the history.
  - When reading data for user to edit vote allocations, it includes "parent" - this is used to chain the records together.
  - When user saves their new allocation, we write a pending_votes entry, with "version" set to a new timeUUID, and "parent" set to the basis data we previously read. Then we propagate this information to policy_new_votes for all the policies that have non-zero votes*, so it can be efficiently used to calculate total_votes and go into the aggregate history. That's a distributed write, so could fail at any step; a background process will clean this up, and pending_votes entries stay there until one or other process successfully completes all those writes and the following total_votes recalc.
    * Important: the exclusion of zero-increment votes is an optimization: the zero votes are only needed (to resolve the conflicts in policy_new_votes across all policies submits two conflicting vote allocations to different policies) if there is actually a conflict to resolve in policy_new_votes, but there may be a lot of them so we avoid writing them unless we have to. We handle this by writing them later if a conflict is actually found when reading back user_policy_votes.
  - When there are conflicting updates in user_policy_votes and in policy_new_votes, the last update will eventually win, which is probably what the user will expect.
  - Once written to policy_new_votes, the pending entry is written as a user_policy_votes column, with timestamp set the same as the pending item, then the user_policy_votes_pending column is deleted.
  - "Penalty" votes: when withdrawing votes from a policy, you only get back a percentage (say 50% - see voting config "vote_withdrawal_penalty_percentage") of the votes. This must participate in conflict resolution so is stored in the vote records.
    - Withdrawals are represented as a negative vote against the policy (conceptually, decrement of policy total), and a negative "penalty" value associated with that vote decrement (conceptually, decrement of the user's balance) - e.g. if you withdraw 100 votes, you record -100 vote increment, and -50 penalty.
    - Each user vote record also tracks the cumulative total of penalty votes per policy for this user, so we don't have to go all the way back through the chain to calculate it. We track it per policy so we can ignore it for deleted policies.
  - When user creates a policy, the records include the created policyID and the initial mandatory vote allocation to  the new policy.
  - To determine the list of entries which add up to make the user's current vote allocation, taking into account conflict resolution:
    - First propagate any user_policy_votes_pending records.
    - Read all the user_policy_votes columns, organize by version -> parent, and find the newest version that has an unbroken chain of extant parent links.
    - If there's a conflict, there will be multiple chains or a branched chain.
      - When we find branched chains, the chain that has the child with the newest timestamp is the winner, and for each of the losing records in the other chains, we:
        - Write zero-increment vote records to policy_new_votes, with timestamp and version for the newest child item which WON the conflict, for each policy in the item which LOST the conflict which has a non-zero vote record. This late write is an optimisation and is needed to make the cross-policy conflict resolution work correctly.
      - If they include policy creations, then we delete the policy. This eventually cleans up (albeit after temporary exposure to other users) if someone manages to double-create, which would double-spend their votes.
      - When those writes have succeeded, delete the conflicted item from user_policy_votes.
    - We could keep a timestamp of parent up to which we have resolved everything. Need to go back at least GC_GRACE_SECONDS each time though.
  - Ignore allocations to any policies that have since been marked deleted, replaced or retired.
  - To calculate unallocated votes, add up all the vote salary entries; then take the last conflict-resolved user_policy_votes entry, subtract from the salary the sum of its newtotal values for all policies, then subtract from this the sum of the penaltytotal values.
  - Propagating to policy_new_votes: see below.

policy_new_votes:
  This records vote allocation changes as users add them. It provides "distributed vote counting" - allows efficient recalculation of current total votes for a policy by reading only one row.
  - Records are created from user_policy_votes.pending_votes, one for every policy that receives a non-zero change in votes (and sometimes zero votes if a conflict was found, see user_policy_votes).
  - All columns come from the corresponding user_policy_votes.pending_votes_ record.
  - Records remain here for a while (configurable in "memcache["voting-config"]) until we can be certain we have all the data for that time, even in the event of node failure and recovery. Until then we can still make vote counts, but it's possible they might change later.
  - To recalculate the current vote allocation for a policy:
    - Read policies.finalized_votes -> count and boundary timeUUID. Start with this count. Figure out which day is the last-fully-finalized day.
    - Init daily vote counts to zero for each day since last finalized day.
    - Read ALL columns in policy_new_votes.
    - Iterate, discarding any items whose "version" column is older than boundary timeUUID: these are already counted
      and will shortly get archived. Put the rest into a map by "version" column.
    - Iterate the map:
      - if the "parent" value (column name) is older than the boundary timeUUID, item passes conflict resolution,
        because its parent has been finalized. Add the increment to the total vote count and the appropriate daily count.
      - else look up the "parent" value in the map and follow the chain back repeatedly:
        - If we reach an entry whose "parent" value is not in the map but is older than the boundary timeUUID, the whole chain is consistent; add the increment for the child value we started with to the total and the appropriate daily count.
        - If we reach an entry whose "parent" value is not in the map but is NOT older than the boundary timeUUID, then the whole chain has been superseded - one of the parents failed conflict resolution. Don't add the value for the child value we started with to the total. Additionally, issue a delete for it.
    All this is not as bad as it sounds; mostly there will be very short chains or single items, and we only have to do all this recalculation when we save vote changes.
    - Write result to policies.total_votes, with the write timestamp set to the time we started the read.
    - Write a column to policy_ranking_current["total-votes"], with the write timestamp set to the time we started the read.
    - Write policy_vote_daily_change[<day>][<policyID>] for each day's count, with the write timestamp set to the time we started the read.
    - For each N-day count we're keeping, write a separate column to policy_ranking_current[<int>"-day"] for each of the last N days' vote changes (reading further back in policy_vote_daily_change if necessary), with the write timestamp set to the time we started the read, and the TTL set to expire just after the last day in the window, i.e. interval - (time until end of today) + a little bit (the TTL is only for expiry; the read query sets a date range).

policy_vote_history:
  History of "finalized" votes. Grouped into a row per date for convenient retrieval.
  Every so often, records from policy_new_votes whose "version" (not "parent") is old enough (memcache["voting-config"].vote_finalize_delay_seconds) are:
  - copied to the appropriate row of policy_vote_history
    We discard any zero values, and we follow the same duplicate-history rules as described under policy_new_votes, except that we actually discard (by not copying them) the duplicate records.
  - misc["vote-history-dates"] is updated
  - updated again in policy_votes_daily and policy_vote_daily_change
  - added to policies.finalized_votes plus its timestamp is updated to that of the newest "version" (not "parent") that we copied
  - deleted from policy_new_votes
  - then redo the usual calcs for policy_new_votes? Or separate process for this.
  Provided the write to "finalized_votes" is done before the deletes, this is safe if it happens to get run by multiple threads in parallel.

parties:
  Assuming we want to track policies by political party, this is the list of parties.

users_by_name:
  This is an index for records in "users" by username.
  - Avoiding duplicate concurrent registration of usernames:
    - To create a user account, we first write to users_by_name, without setting user_id, and with the timestamp on the write transaction set to a DECREASING value, -now. This way later writes will not overwrite earlier ones - mostly!
    - Then we read back and check that the registered_timestamp is the same as we just wrote. If it is, we "won" the username and can set the user_id column. If it's not, someone else already has it.
      (There are still some corner cases:
        1. Two writes started at about the same time can interleave their reads and writes such that each client sees its own write win (even though the earliest-timestamped one REALLY wins).
        2. Failed writes can eventually reappear when a node recovers.
      It's not possible to make it bulletproof without a centralized locking service. For now, clients will treat failed writes as "someone else has the username", which protects against case 2; and in case 1, one of them will win, we'll end up with a stray entry in "users" with no "users_by_name" pointing to it, and only the winning user will be able to log in. This is obscure enough that I'm ignoring it for now; background processes can clean up the other one or log for manual attention.)

policy_ranking_current["total-votes"]:
  Allows rapid retrieval of current sorted ranking (by total votes) for the front page.
  It means we only need a one-row read instead of a read for each policy record.
  Write a column to it every time we update at a policy's total_votes.
  Reader just retrieves all columns, iterates into a LinkedHashMap {policyID -> [count, timestamp]}, discards (and issues deletes for) all but the newest-timestamped for any policyID that appears twice, then reads map.entrySet() in order - they're already sorted.

policy_ranking_current[<int>"-day"]:
  Read everything with date range from N days ago, iterate and add up votes for each policy, sort on client by vote total. Client doesn't need to do conflict resolution this time.
  (Will generalize to 4-day, weekly, whatever.)

policy_ranking_history:
  Keeps historical record of rankings.
  TODO: to be accurate in the face of late-resolved conflicts, we can only update this when copying votes to policy_vote_history. Figure out the data and algorithm for this.

comments:
  Time-ordered comments for each policy and category. Stored this way so we can time-slice query for "what's new" lists and retrieve by individual comment ID.
  A "comment ID" is:
      "policy_" or "category_"
      ...plus row key - either the policyID or the categoryID (and others if we need them)
      ...plus the column name - the comment's own unique timeUUID.
  Maybe use subinterfaces in the data API for the different IDs / key types, then we can have uniform "get comment" implementations that just look in the different column families.
  - deleted flag: to preserve the threading structure of remaining comments, deleted items are retained as placeholders but not displayed. We can either delete the "text" and "subject" fields when marking deleted, or keep them and let moderators see them.
  When creating comments, write a "comments_" column to ipaddresses, and to either users or anonymous_users, with that comment ID.

comments_threaded:
  Easy retrieval of comments in thread structure. Column name is <parentID>:<childID>:<childID> etc. according to nested structure. This means we can slice query by, say, <parentID>:<childID> to get time-ordered comments within any nesting level.

moderation:
  Time-ordered items that have been flagged for moderation ("Report this" feature) by users of the system.
  This will be the mechanism for reporting abusive comments/policy creation/tags/users, and also for reporting user login problems. 
  We'll have a UI for those with moderator permissions to view these and delete them (or archive them somewhere maybe) once the appropriate action has been taken.
  Split into "policies", "tags", "comments", "users" categories since they may have separate permissions.
  When someone reports something, we can let them enter an optional reason, and we record their userID if they're logged in.

memcache:
  Contains various single-row stuff for quick-lookups to avoid the need to iterate all keys on every request, and provide things in sorted order; runtime and config data.
  This is for small-ish rows so in-memory row cache can be enabled.

  memcache["vote-salary"]:
    - Vote salary, same for all users. Balance per user calculated from this based on their registration date.

  memcache["active-policies"]:
  - list of all active policy IDs, updated whenever they're added/disabled.
    Key is whatever we want to sort by - the name, or party+name, or date, or something.
    Value can be the ID, or make it a supercolumn with more data as needed.
    Probably pretty much every request will read this.

  memcache["active-parties"]:
  - list of all active party IDs, updated whenever they're added/disabled.
    As above, key is whatever we want to sort by, value is whatever we need in the list view.

  memcache["active-categories"]:
  - list of all categories.

  memcache["tags-by-name"]:
  - index of tag name -> tag ID, to allow name lookup and prefix queries for tag name autocomplete.
  If duplicate tags get created for a name, the one in here will be the "live" one and the others will get cleaned up and merged later.

  memcache["webapp-instances"]:
  - each webapp's background runner reinserts its value every (say) 2 mins.
    The column TTL ensures the entry expires after a while if the server dies.
    Main purpose is to allow each background runner to easily determine how many webapp nodes are in the cluster, so it can set frequencies of background tasks accordingly, and maybe other monitoring info as needed.

  memcache["voting-config"]:
  - current_votes_finalize_delay: seconds to keep items in policy_new_votes waiting for conflict resolution before background process "finalizes" them as above and archives to policy_vote_history. This has to be the same as Cassandra GC_GRACE_SECONDS, since that's the longest it can possibly take (in the event of server failure and recovery) for late writes to reappear (e.g. a failed write that actually made it to disk on a machine just before it dies, which later recovers). It's probably a few days.

  memcache["featured-policies"]:
    - policy IDs listed here appear in "featured policies" list on front page.

  memcache["message-types"]:
    - String.format strings for user_messages entries so we can change the text as desired.
    e.g. things like POLICY_RETIRED = "The policy '%{old_policy_name}' that you voted on was retired. The owner suggests you vote on '%{new_policy_name}' instead."

misc:
  One-row datasets for which we may not want row caching enabled.

  misc["vote-history-dates"]:
    - for driving iteration over all date records in the system. Updated when finalizing votes to vote history.

  misc["log-hours"]:
    - for driving iteration over all log records in the system. Low write load. Updated about once per hour per node when writing log or performance log records.


In a separate keyspace, so we can set different replication / consistency rules:
  Logging can use write consistency level 1 (or ANY, or don't care) and read consistency level something-else (with WF 1 it needs to be ALL to guarantee seeing everything; or allow manually falling back to lower levels so we can still read something if some nodes are down):

log:
  Logging from the webapps:
    - Activity/audit logging
    - Temporary debug logging

performance:
    - Periodic performance log records, etc.

Both are grouped in rows by hour.
TTL can be set for records that we don't need to keep for auditing, say a few days or a few weeks.
We first add a column to misc["log-hours"] if we haven't written it yet for this server and hour.


Other stuff:
------------
Merging policies:
  We don't support this; we support "retiring" with a suggested alternative policy to vote on.
  Users who voted on the retired policy get all their votes back so they can allocate them again.
  Votes are not automatically allocated to the alternative policy.
  Policies marked as "retired" will not show vote counts or rankings on their edit page (and won't participate in ranking recalcs), but just a link to the suggested replacement policy.

Activities that do things with policies (e.g. show/edit user votes for policies) must check the state for the policyID. Checking in memcache["active-policies"] should generally be enough. There's no locking so it's impossible to make setting the policy retired behave as an instant cutoff; the best we could do is have a timestamp and retrospectively undo stuff. Probably not a big deal... and if we design for it, we can do things like cache the list of active policies for a few minutes in each webapp.

Similar considerations with deleted users.


Background operations:
----------------------
Each instance of the webapp will have a background scheduler thread (using Quartz library or similar) that runs periodic tasks. (We could use Hadoop on top of Cassandra, but as I understand it, that needs "special" job control nodes which are single points of failure.) So that we have no single point of failure and can work with any number of webapp instances, tasks are run on all nodes, maybe with somewhat randomized scheduling according to the current number of webapp instances, and tasks are designed to be idempotent, i.e. they can safely be run multiple times or in parallel, and duplicate writes / deletes won't matter.

Tasks:

- Occasionally (maybe a few times a day), archive from policy_new_votes to policy_vote_history as described above, according to current_votes_finalize_delay, and update finalized baseline counts in policies.

- Periodically look for user_policy_votes records with pending_votes columns, and re-do/complete the writes to policy_new_votes, and the recalc of policies.total_votes, then move the user_policy_votes.pending_votes column to a user_policy_votes.votes column.

- Re-read user_policy_votes and apply conflict resolution - sorts out the mess for double-created policies. Maybe combine with the pending_votes operation above.

- Every few minutes, update policy_ranking_current["total-votes"] from policies.total_votes across all policies. This corrects current rankings if any writes to this row get lost.

- TODO: what updates needed for lost writes in the 3-day ranking calculation?

- Every few minutes, recalc policies.total_votes for each one (last-resort protection against failures during recalc after updates to policy_new_votes). Note this is still needed given the use of user_policy_votes and pending_votes.

- Rarely, check for stray entries in users_by_name with no entry in users, and vice versa; these can result from failures part-way through the user registration process or simultaneous registration of same username; just delete them if last modified more than GC_GRACE_SECONDS ago so the username is freed up for reuse.

- Every few minutes, update active-policies / active-parties records from entries in policies and parties and their states: caters for lost writes when deleting/creating policies/parties.

- Apply policy deletions. "deletion" is an "obliterate" operation used when there's an edit conflict at policy creation, - see user_policy_votes; this will be rare; or for when someone creates something abusive. Find policies with state="deletion-pending" which have been in that state longer than GC_GRACE_SECONDS, and delete all records for that ID elsewhere in the system (expensive), then set state to "deleted" and leave there.

- Merge duplicate tags. Read all records from tags table, find duplicates: winner is the one that is in memcache["tags-by-name"], use merge/delete algorithm as described in "tags" above. Also ensure memcache["tags-by-name"] is up-to-date - delete stray entries (lost write on rename), and add missing entries (lost write on tag creation).

- Occasionally (few hours) - fixup policies per category and policies per tag: read policies.category and all policies.tag_ records for each policy, write categories.policy_ and tags.policy_ records with TTL.


Roles in the system:
--------------------
Normal user permissions: probably everyone will have these initially, but may be useful to be able to remove them for users who are abusive:

- Log in
- Create policy
- Create tag
- Start new comment threads
- Reply to existing comment threads
- Report items (policies, tags, comments) for abuse
- Edit policies: suggest replacement policies

Moderator/admin permissions:

- Edit users
- Suspend/ban users
- Edit policies: category
- Edit policies: name / description
- Edit policies: delete
- Edit policies: retire
- Edit policies: mark as featured policy
- Moderate comments: delete
- Moderate comments: edit
- Edit tags: delete
- Edit tags: edit/rename
- Edit tags: merge


Questions remaining:
--------------------
- Detection of duplicate accounts. Do we require users to have unique email addresses? What else?


Detailed spec for each operation:
---------------------------------
TODO - finish this

User:
    add user:
      username uniqueness check
      create user record with initial salary balance
      create intitial zero vote record

    get number of policies created

    get number of policies voted for
        (current ranking only - removing all votes removes it from this count)

User vote allocation:
    add new periodic vote bundle (weekly job, or on demand when user record read):
      write:
        vote_salary_last_paid = now,
        new column users.vote_salary_<timestamp> = NEW_VOTES_PER_WEEK

    read total unallocated votes:

    allocate votes to policy:

    unallocate votes from policy:

    delete user:

Policy:
    list current total votes ranking:
        ordered list of policy IDs with total vote counts
    list current N-day ranking:
        ordered list of policy IDs with in-window vote counts

Party:

Tag:
    add tag
    delete tag
    rename tag
    merge tags
    list all tags
    list all tags with policy count (for tag cloud)
    list tags by string prefix

Policy tag allocation:
    list all policies with a given tag
    assign tag to policy
    remove tag from policy

Moderation queue:
    report login/registration problem
    report user
    report policy (also used for requesting merges)
    report comment
    report tag (also used for requesting merges)
    list login problems for moderation
    list users for moderation
    list policies for moderation
    list comments for moderation
    list tags for moderation
    mark item as actioned

Log:

Background operations:
