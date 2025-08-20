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

#include "fuzzer_common.h"

#include <signal.h>

#include "dex/class_accessor-inl.h"
#include "handle_scope-inl.h"
#include "interpreter/unstarted_runtime.h"
#include "noop_compiler_callbacks.h"
#include "runtime_intrinsics.h"
#include "scoped_thread_state_change-inl.h"

namespace art {
namespace fuzzer {

// Global variable to signal LSAN that we are not leaking memory.
uint8_t* allocated_signal_stack = nullptr;

static std::string GetDexFileName(const std::string& jar_name) {
  // The jar files are located in the data directory within the directory of the fuzzer's binary.
  std::string executable_dir = android::base::GetExecutableDirectory();

  std::string result =
      android::base::StringPrintf("%s/data/%s.jar", executable_dir.c_str(), jar_name.c_str());

  return result;
}

static std::vector<std::string> GetLibCoreDexFileNames() {
  std::vector<std::string> result;
  const std::vector<std::string> modules = {
      "core-oj",
      "core-libart",
      "okhttp",
      "bouncycastle",
      "apache-xml",
      "core-icu4j",
      "conscrypt",
  };
  result.reserve(modules.size());
  for (const std::string& module : modules) {
    result.push_back(GetDexFileName(module));
  }
  return result;
}

static std::string GetClassPathOption(const char* option,
                                      const std::vector<std::string>& class_path) {
  return option + android::base::Join(class_path, ':');
}

void FuzzerInitialize() {
  // Set logging to error and above to avoid warnings about unexpected checksums.
  android::base::SetMinimumLogSeverity(android::base::ERROR);

  // Create runtime.
  RuntimeOptions options;
  {
    static NoopCompilerCallbacks callbacks;
    options.push_back(std::make_pair("compilercallbacks", &callbacks));
  }

  std::string boot_class_path_string =
      GetClassPathOption("-Xbootclasspath:", GetLibCoreDexFileNames());
  options.push_back(std::make_pair(boot_class_path_string, nullptr));

  // Instruction set.
  options.push_back(std::make_pair(
      "imageinstructionset", reinterpret_cast<const void*>(GetInstructionSetString(kRuntimeISA))));

  if (!Runtime::Create(options, false)) {
    LOG(FATAL) << "We should always be able to create the runtime";
    UNREACHABLE();
  }

  interpreter::UnstartedRuntime::Initialize();
  Runtime::Current()->GetClassLinker()->RunEarlyRootClinits(Thread::Current());
  InitializeIntrinsics();
  Runtime::Current()->RunRootClinits(Thread::Current());

  // Check for heap corruption before running the fuzzer.
  Runtime::Current()->GetHeap()->VerifyHeap();

  // Runtime::Create acquired the mutator_lock_ that is normally given away when we
  // Runtime::Start, give it away now with `TransitionFromSuspendedToRunnable` until we figure out
  // how to start a Runtime.
  Thread::Current()->TransitionFromRunnableToSuspended(ThreadState::kNative);

  // Query the current stack and add it to the global variable. Otherwise LSAN complains about a
  // non-existing leak.
  stack_t ss;
  if (sigaltstack(nullptr, &ss) == -1) {
    PLOG(FATAL) << "sigaltstack failed";
  }
  allocated_signal_stack = reinterpret_cast<uint8_t*>(ss.ss_sp);
}

ALWAYS_INLINE std::unique_ptr<StandardDexFile> VerifyDexFile(const uint8_t* data,
                                                             size_t size,
                                                             const std::string& location) {
  // Do not verify the checksum as we only care about the DEX file contents,
  // and know that the checksum would probably be erroneous (i.e. random).
  constexpr bool kVerifyChecksum = false;

  std::unique_ptr<StandardDexFile> dex_file(
      new StandardDexFile(data,
                          location,
                          /*location_checksum=*/0,
                          /*oat_dex_file=*/nullptr,
                          std::make_shared<MemoryDexFileContainer>(data, size)));

  std::string error_msg;
  if (!dex::Verify(dex_file.get(), dex_file->GetLocation().c_str(), kVerifyChecksum, &error_msg)) {
    dex_file.reset();
  }
  return dex_file;
}

const ClassLinker::DexCacheData* FuzzerCommonHelper::GetDexCacheData(
    Runtime* runtime, const StandardDexFile* dex_file) {
  Thread* self = Thread::Current();
  ReaderMutexLock mu(self, *Locks::dex_lock_);
  ClassLinker* class_linker = runtime->GetClassLinker();
  const ClassLinker::DexCacheData* cached_data = class_linker->FindDexCacheDataLocked(*dex_file);
  return cached_data;
}

ALWAYS_INLINE bool VerifyClasses(jobject class_loader, const StandardDexFile* dex_file) {
  bool passed_class_verification = true;
  Runtime* runtime = Runtime::Current();
  CHECK(runtime != nullptr);
  ClassLinker* class_linker = runtime->GetClassLinker();
  ScopedObjectAccess soa(Thread::Current());

  // Scope for the handles
  {
    StackHandleScope<4> scope(soa.Self());
    Handle<mirror::ClassLoader> h_loader =
        scope.NewHandle(soa.Decode<mirror::ClassLoader>(class_loader));
    MutableHandle<mirror::Class> h_klass(scope.NewHandle<mirror::Class>(nullptr));
    MutableHandle<mirror::DexCache> h_dex_cache(scope.NewHandle<mirror::DexCache>(nullptr));
    MutableHandle<mirror::ClassLoader> h_dex_cache_class_loader = scope.NewHandle(h_loader.Get());

    for (const ClassAccessor accessor : dex_file->GetClasses()) {
      h_klass.Assign(
          class_linker->FindClass(soa.Self(), *dex_file, accessor.GetClassIdx(), h_loader));
      // Ignore classes that couldn't be loaded since we are looking for crashes during
      // class/method verification.
      if (h_klass == nullptr || h_klass->IsErroneous()) {
        // Treat as failure to pass verification
        passed_class_verification = false;
        soa.Self()->ClearException();
        continue;
      }
      if (&h_klass->GetDexFile() != dex_file) {
        // Skip a duplicate class (as the resolved class is from another dex file). This can happen
        // e.g. if we have a class named "sun.misc.Unsafe" in fuzz.dex.
        continue;
      }

      h_dex_cache.Assign(h_klass->GetDexCache());

      // The class loader from the class's dex cache is different from the dex file's class loader
      // for boot image classes e.g. java.util.AbstractCollection.
      h_dex_cache_class_loader.Assign(h_klass->GetDexCache()->GetClassLoader());

      CHECK(h_klass->IsResolved()) << h_klass->PrettyClass();
      verifier::FailureKind failure_kind =
          class_linker->VerifyClass(soa.Self(),
                                    /* verifier_deps= */ nullptr,
                                    h_klass,
                                    // Don't abort on verification errors.
                                    verifier::HardFailLogMode::kLogWarning);

      DCHECK_EQ(h_klass->IsErroneous(), failure_kind == verifier::FailureKind::kHardFailure);
      if (failure_kind == verifier::FailureKind::kHardFailure) {
        passed_class_verification = false;
        // ClassLinker::VerifyClass throws, so we clear it before we continue.
        CHECK(soa.Self()->IsExceptionPending());
        soa.Self()->ClearException();
      }

      CHECK(h_klass->ShouldVerifyAtRuntime() ||
            h_klass->IsVerifiedNeedsAccessChecks() ||
            h_klass->IsVerified() ||
            h_klass->IsErroneous())
          << h_klass->PrettyDescriptor() << ": state=" << h_klass->GetStatus();

      soa.Self()->AssertNoPendingException();
    }
  }

  // Clear the arena pool to free RAM. The next iteration won't be referencing the pool we just
  // used.
  Runtime::Current()->ReclaimArenaPoolMemory();

  // Delete weak root to the DexCache before removing a DEX file from the cache. This is usually
  // handled by the GC, but since we are not calling it every iteration, we need to delete them
  // manually.
  const ClassLinker::DexCacheData* dex_cache_data =
      FuzzerCommonHelper::GetDexCacheData(runtime, dex_file);
  if (dex_cache_data != nullptr && dex_cache_data->weak_root != nullptr) {
    soa.Env()->GetVm()->DeleteWeakGlobalRef(soa.Self(), dex_cache_data->weak_root);
  }

  // Mimic DexFile_closeDexFile.
  RemoveNativeDebugInfoForDex(soa.Self(), dex_file);
  class_linker->RemoveDexFromCaches(*dex_file);

  // Delete global ref and unload class loader to free RAM.
  soa.Env()->GetVm()->DeleteGlobalRef(soa.Self(), class_loader);

  return passed_class_verification;
}

jobject RegisterDexFileAndGetClassLoader(Runtime* runtime, const StandardDexFile* dex_file) {
  Thread* self = Thread::Current();
  ScopedObjectAccess soa(self);
  ClassLinker* class_linker = runtime->GetClassLinker();
  const std::vector<const DexFile*> dex_files = {dex_file};
  jobject class_loader = class_linker->CreatePathClassLoader(self, dex_files);
  ObjPtr<mirror::ClassLoader> cl = self->DecodeJObject(class_loader)->AsClassLoader();
  class_linker->RegisterDexFile(*dex_file, cl);
  return class_loader;
}

}  // namespace fuzzer
}  // namespace art
