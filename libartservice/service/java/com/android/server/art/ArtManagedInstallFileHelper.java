/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.art;

import static com.android.server.art.model.ValidationResult.INVALID_SDM_BAD_APK_SIGNATURE;
import static com.android.server.art.model.ValidationResult.INVALID_SDM_BAD_SDM_SIGNATURE;
import static com.android.server.art.model.ValidationResult.INVALID_SDM_INVALID_ISA;
import static com.android.server.art.model.ValidationResult.INVALID_SDM_SIGNATURE_MISMATCH;
import static com.android.server.art.model.ValidationResult.RESULT_ACCEPTED;
import static com.android.server.art.model.ValidationResult.RESULT_SHOULD_DELETE_AND_CONTINUE;
import static com.android.server.art.model.ValidationResult.RESULT_SHOULD_FAIL;
import static com.android.server.art.model.ValidationResult.RESULT_UNRECOGNIZED;
import static com.android.server.art.model.ValidationResult.UNRECOGNIZED_PATH;

import android.annotation.CheckResult;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.content.pm.SigningInfoException;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.art.flags.Flags;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.art.model.ValidationResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper class for <i>ART-managed install files</i> (files installed by Package Manager
 * and managed by ART).
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public final class ArtManagedInstallFileHelper {
    private static final List<String> FILE_TYPES = List.of(ArtConstants.DEX_METADATA_FILE_EXT,
            ArtConstants.PROFILE_FILE_EXT, ArtConstants.SECURE_DEX_METADATA_FILE_EXT);
    private static final List<String> SDM_SUFFIXES =
            Utils.getNativeIsas()
                    .stream()
                    .map(isa -> "." + isa + ArtConstants.SECURE_DEX_METADATA_FILE_EXT)
                    .toList();

    /** @hide */
    @VisibleForTesting public static Injector sInjector = new Injector();

    private ArtManagedInstallFileHelper() {}

    /**
     * Returns whether the file at the given path is an <i>ART-managed install file</i>. This
     * is a pure string operation on the input and does not involve any I/O.
     */
    public static boolean isArtManaged(@NonNull String path) {
        return FILE_TYPES.stream().anyMatch(ext -> path.endsWith(ext));
    }

    /**
     * Returns the subset of the given paths that are paths to the <i>ART-managed install files</i>
     * corresponding to the given APK path. This is a pure string operation on the inputs and does
     * not involve any I/O.
     *
     * Note that the files in different directories than the APK are not considered corresponding to
     * the APK.
     */
    public static @NonNull List<String> filterPathsForApk(
            @NonNull List<String> paths, @NonNull String apkPath) {
        Set<String> candidates =
                FILE_TYPES.stream()
                        .flatMap(ext
                                -> ext.equals(ArtConstants.SECURE_DEX_METADATA_FILE_EXT)
                                        ? SDM_SUFFIXES.stream().map(suffix
                                                  -> Utils.replaceFileExtension(apkPath, suffix))
                                        : Stream.of(Utils.replaceFileExtension(apkPath, ext)))
                        .collect(Collectors.toSet());
        return paths.stream().filter(path -> candidates.contains(path)).toList();
    }

    /**
     * Rewrites the path to the <i>ART-managed install file</i> so that it corresponds to the given
     * APK path. This is a pure string operation on the inputs and does not involve any I/O.
     *
     * Note that the result path is always in the same directory as the APK, in order to correspond
     * to the APK.
     *
     * @throws IllegalArgumentException if {@code originalPath} does not represent an <i>ART-managed
     *         install file</i>
     */
    public static @NonNull String getTargetPathForApk(
            @NonNull String originalPath, @NonNull String apkPath) {
        for (String ext : FILE_TYPES) {
            if (!ext.equals(ArtConstants.SECURE_DEX_METADATA_FILE_EXT)
                    && originalPath.endsWith(ext)) {
                return Utils.replaceFileExtension(apkPath, ext);
            }
        }
        if (originalPath.endsWith(ArtConstants.SECURE_DEX_METADATA_FILE_EXT)) {
            for (String suffix : SDM_SUFFIXES) {
                if (originalPath.endsWith(suffix)) {
                    return Utils.replaceFileExtension(apkPath, suffix);
                }
            }
            AsLog.w("SDM filename '" + originalPath
                    + "' does not contain a valid instruction set name");
            Path dirname = Paths.get(apkPath).getParent();
            Path basename = Paths.get(originalPath).getFileName();
            return (dirname != null ? dirname.resolve(basename) : basename).toString();
        }
        throw new IllegalArgumentException(
                "Illegal ART managed install file path '" + originalPath + "'");
    }

    /**
     * Validates ART-managed install files.
     *
     * This operation involves I/O.
     *
     * @param paths the list of ART-managed install files to be validated. For each file, if it has
     *         corresponding non-ART-managed install files, (e.g., a corresponding .apk file for an
     *         .sdm file), they must be in the same directory as the file itself.
     */
    @CheckResult
    @FlaggedApi(Flags.FLAG_ART_MANAGED_INSTALL_FILES_VALIDATION_API)
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    public static @NonNull List<ValidationResult> validateFiles(@NonNull List<String> paths) {
        List<ValidationResult> results = new ArrayList<>();
        for (String path : paths) {
            if (path.endsWith(ArtConstants.SECURE_DEX_METADATA_FILE_EXT)) {
                results.add(validateSdmFile(path));
            } else if (path.endsWith(ArtConstants.DEX_METADATA_FILE_EXT)
                    || path.endsWith(ArtConstants.PROFILE_FILE_EXT)) {
                results.add(new ValidationResult(path, RESULT_ACCEPTED));
            } else {
                results.add(new ValidationResult(path, RESULT_UNRECOGNIZED, UNRECOGNIZED_PATH,
                        String.format(
                                "Path '%s' does not represent an ART-managed install file", path)));
            }
        }
        return results;
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private static @NonNull ValidationResult validateSdmFile(@NonNull String path) {
        String apkPath = null;
        for (String suffix : SDM_SUFFIXES) {
            if (path.endsWith(suffix)) {
                apkPath = path.substring(0, path.length() - suffix.length()) + ".apk";
            }
        }
        if (apkPath == null) {
            return new ValidationResult(path, RESULT_SHOULD_DELETE_AND_CONTINUE,
                    INVALID_SDM_INVALID_ISA,
                    String.format("Missing or invalid instruction set name in SDM filename '%s'",
                            Paths.get(path).getFileName()));
        }

        SigningInfo apkSigningInfo;
        try {
            // SDM is a format introduced in Android 16, so we don't need to support older signature
            // schemes.
            apkSigningInfo =
                    sInjector.getVerifiedSigningInfo(apkPath, SigningInfo.VERSION_SIGNING_BLOCK_V3);
        } catch (SigningInfoException e) {
            return new ValidationResult(path, RESULT_SHOULD_DELETE_AND_CONTINUE,
                    INVALID_SDM_BAD_APK_SIGNATURE,
                    String.format("Failed to verify APK signatures for '%s': %s",
                            Paths.get(apkPath).getFileName(), e.getMessage()));
        }

        SigningInfo sdmSigningInfo;
        try {
            // SDM is a format introduced in Android 16, so we don't need to support older signature
            // schemes.
            sdmSigningInfo =
                    sInjector.getVerifiedSigningInfo(path, SigningInfo.VERSION_SIGNING_BLOCK_V3);
        } catch (SigningInfoException e) {
            return new ValidationResult(path, RESULT_SHOULD_DELETE_AND_CONTINUE,
                    INVALID_SDM_BAD_SDM_SIGNATURE,
                    String.format("Failed to verify SDM signatures for '%s': %s",
                            Paths.get(path).getFileName(), e.getMessage()));
        }

        if (!apkSigningInfo.signersMatchExactly(sdmSigningInfo)) {
            return new ValidationResult(path, RESULT_SHOULD_DELETE_AND_CONTINUE,
                    INVALID_SDM_SIGNATURE_MISMATCH,
                    String.format("SDM signatures are inconsistent with APK (SDM filename: '%s', "
                                    + "APK filename: '%s')",
                            Paths.get(path).getFileName(), Paths.get(apkPath).getFileName()));
        }

        return new ValidationResult(path, RESULT_ACCEPTED);
    }

    /**
     * Injector pattern for testing purpose.
     *
     * @hide
     */
    @VisibleForTesting
    public static class Injector {
        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        @NonNull
        public SigningInfo getVerifiedSigningInfo(@NonNull String path,
                /* @AppSigningSchemeVersion */ int minAppSigningSchemeVersion)
                throws SigningInfoException {
            return PackageManager.getVerifiedSigningInfo(path, minAppSigningSchemeVersion);
        }
    }
}
