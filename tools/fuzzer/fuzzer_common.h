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

#ifndef ART_TOOLS_FUZZER_FUZZER_COMMON_H_
#define ART_TOOLS_FUZZER_FUZZER_COMMON_H_

#include <cstdint>
#include <string>

#include "android-base/file.h"
#include "android-base/strings.h"
#include "base/file_utils.h"
#include "base/mem_map.h"
#include "class_linker.h"
#include "dex/dex_file_verifier.h"
#include "dex/standard_dex_file.h"
#include "interpreter/unstarted_runtime.h"
#include "jit/debugger_interface.h"
#include "jni/java_vm_ext.h"
#include "runtime.h"
#include "verifier/class_verifier.h"
#include "well_known_classes.h"

namespace art {
namespace fuzzer {

// Returns the DEX file created using the parameters, if it could be verified. Returns nullptr if
// the DEX file couldn't be verified.
ALWAYS_INLINE std::unique_ptr<StandardDexFile> VerifyDexFile(const uint8_t* data,
                                                             size_t size,
                                                             const std::string& location);

// A class to be friends with ClassLinker and access the internal FindDexCacheDataLocked method.
class FuzzerCommonHelper {
 public:
  static const ClassLinker::DexCacheData* GetDexCacheData(Runtime* runtime,
                                                          const StandardDexFile* dex_file)
      REQUIRES_SHARED(Locks::mutator_lock_);
};

// Verifies all classes within a DEX file. Returns true iff all classes could be verified.
ALWAYS_INLINE bool VerifyClasses(jobject class_loader, const StandardDexFile* dex_file);

// Registers a DEX file with the given runtime.
jobject RegisterDexFileAndGetClassLoader(Runtime* runtime, const StandardDexFile* dex_file);

// Initialization for all fuzzers that need a Runtime.
void FuzzerInitialize();

}  // namespace fuzzer
}  // namespace art

#endif  // ART_TOOLS_FUZZER_FUZZER_COMMON_H_
