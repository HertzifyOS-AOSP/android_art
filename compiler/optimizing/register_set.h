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

#ifndef ART_COMPILER_OPTIMIZING_REGISTER_SET_H_
#define ART_COMPILER_OPTIMIZING_REGISTER_SET_H_

#include "base/logging.h"
#include "base/macros.h"
#include "base/bit_utils.h"
#include "base/value_object.h"

namespace art HIDDEN {

class RegisterSet : public ValueObject {
 public:
  static RegisterSet Empty() { return RegisterSet(); }
  static RegisterSet AllFpu() { return RegisterSet(0, -1); }

  void AddCoreRegisterSet(uint32_t registers) {
    core_registers_ |= registers;
  }

  void AddFpuRegisterSet(uint32_t registers) {
    floating_point_registers_ |= registers;
  }

  void AddCoreRegister(uint32_t reg) {
    DCHECK_LT(reg, BitSizeOf<uint32_t>());
    core_registers_ |= 1u << reg;
  }

  void AddFpuRegister(uint32_t reg) {
    DCHECK_LT(reg, BitSizeOf<uint32_t>());
    floating_point_registers_ |= 1u << reg;
  }

  bool ContainsCoreRegister(uint32_t id) const {
    return Contains(core_registers_, id);
  }

  bool ContainsFloatingPointRegister(uint32_t id) const {
    return Contains(floating_point_registers_, id);
  }

  static bool Contains(uint32_t register_set, uint32_t reg) {
    return (register_set & (1 << reg)) != 0;
  }

  size_t GetNumberOfRegisters() const {
    return POPCOUNT(core_registers_) + POPCOUNT(floating_point_registers_);
  }

  uint32_t GetCoreRegisterSet() const {
    return core_registers_;
  }

  uint32_t GetFloatingPointRegisterSet() const {
    return floating_point_registers_;
  }

  static uint32_t RegisterSet::* GetRegisterFieldAccessor(bool fp) {
    return fp ? &RegisterSet::floating_point_registers_ : &RegisterSet::core_registers_;
  }

  RegisterSet Union(const RegisterSet& other) const {
    return {core_registers_ | other.core_registers_,
            floating_point_registers_ | other.floating_point_registers_};
  }

  RegisterSet Intersect(const RegisterSet& other) const {
    return {core_registers_ & other.core_registers_,
            floating_point_registers_ & other.floating_point_registers_};
  }

  RegisterSet Subtract(const RegisterSet& other) const {
    return {core_registers_ & ~other.core_registers_,
            floating_point_registers_ & ~other.floating_point_registers_};
  }

 private:
  RegisterSet() : core_registers_(0), floating_point_registers_(0) {}
  RegisterSet(uint32_t core, uint32_t fp) : core_registers_(core), floating_point_registers_(fp) {}

  uint32_t core_registers_;
  uint32_t floating_point_registers_;
};

}  // namespace art

#endif  // ART_COMPILER_OPTIMIZING_REGISTER_SET_H_
