# Copyright (C) 2023 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

APP_OPTIM := release
APP_PLATFORM := android-24
APP_ABI := arm64-v8a
APP_CFLAGS := -O3 -DPKGNAME=io/github/dovecoteescapee/byedpi/core -DCLSNAME=TProxyService
APP_CPPFLAGS := -O3 -std=c++11
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
# Pixel 8/9 16 KB page size
APP_LDFLAGS := -Wl,-z,max-page-size=16384
NDK_TOOLCHAIN_VERSION := clang
