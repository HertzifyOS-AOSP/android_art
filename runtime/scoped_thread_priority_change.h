/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef ART_RUNTIME_SCOPED_THREAD_PRIORITY_CHANGE_H_
#define ART_RUNTIME_SCOPED_THREAD_PRIORITY_CHANGE_H_

namespace art HIDDEN {

// Supports temporary increases of priority to NORMAL priority in critical code.  In order to
// minimize races with other priority changes, we follow the protocol described in
// priorities.md.
//
// We err on the side of safety, at the expense of some extra overhead. Currently needs 2
// setpriority() and 3 getpriority() calls in the typical case in which it actually changes
// priority. Hence not appropriate for very lightweight operations.
//
// Just sets kernel niceness; may not interact correctly with Java calls that touch thread
// priority inside the range of the ScopedPriorityChange. (If the code makes Java calls,
// it probably doesn't need this anyway.)
//
// This is designed to work correctly for other asynchronous priority changes made through
// java.lang.Thread and its priority caching. Since there otherwise is no atomic priority update
// mechanism, we cannot 100% avoid stepping on other kinds of asynchronous priority changes, but
// we try to minimize that probability.
//
// Nested uses of ThreadPriorityChange are fine, though the inner one may need to make a
// second getpriority() system call to determine that it has nothing to do.
class ScopedPriorityChange {
 public:
  // Constructor by itself does nothing, allowing the actual priority change to be inside a
  // conditional.
  explicit ScopedPriorityChange(Thread* self) : self_(self), priority_changed_(false) {}

  ~ScopedPriorityChange() REQUIRES_SHARED(Locks::mutator_lock_);

  // Set niceness of current thread to zero if it is currently positive, and it is safe to do so.
  // Should be called at most once during the lifetime of the ScopedPriorityChange.
  void SetToNormalOrBetter() REQUIRES_SHARED(Locks::mutator_lock_);

 private:
  Thread* self_;
  bool priority_changed_;
};

}  // namespace art

#endif  // ART_RUNTIME_SCOPED_THREAD_PRIORITY_CHANGE_H_
