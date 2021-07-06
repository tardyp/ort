/*
 * Copyright (C) 2021 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.scanner.experimental

import com.vdurmont.semver4j.Semver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.should

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.ossreviewtoolkit.model.ArtifactProvenance
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.KnownProvenance
import org.ossreviewtoolkit.model.LicenseFinding

import java.time.Instant

import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.RepositoryProvenance
import org.ossreviewtoolkit.model.ScanResult
import org.ossreviewtoolkit.model.ScanSummary
import org.ossreviewtoolkit.model.ScannerDetails
import org.ossreviewtoolkit.model.SourceCodeOrigin
import org.ossreviewtoolkit.model.TextLocation
import org.ossreviewtoolkit.model.UnknownProvenance
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.DownloaderConfiguration
import org.ossreviewtoolkit.model.config.ScannerConfiguration
import org.ossreviewtoolkit.model.yamlMapper
import org.ossreviewtoolkit.scanner.ScanException
import org.ossreviewtoolkit.scanner.ScannerCriteria
import org.ossreviewtoolkit.utils.test.containExactly
import java.io.File

class ExperimentalScannerTest : WordSpec() {
    private val artifactProvenance = ArtifactProvenance(RemoteArtifact("url", Hash.NONE))
    private val pkgWithArtifactProvenance = Package.EMPTY.copy(
        id = Identifier("Maven:group:artifact:1.0"),
        sourceArtifact = artifactProvenance.sourceArtifact
    )

    private val repositoryProvenance = RepositoryProvenance(
        vcsInfo = VcsInfo(VcsType.UNKNOWN, "url", "revision", ""),
        resolvedRevision = "resolvedRevision"
    )
    private val pkgWithRepositoryProvenance = Package.EMPTY.copy(
        id = Identifier("Maven:group:repository:1.0"),
        vcsProcessed = repositoryProvenance.vcsInfo
    )

    init {
        "Creating the experimental scanner" should {
            "throw an exception if no scanner wrappers are provided" {
                shouldThrow<ScanException> {
                    createScanner()
                }
            }
        }

        "Scanning with a package based remote scanner" should {
            "return a scan result for each package" {
                val scannerWrapper = spyk(FakePackageBasedRemoteScannerWrapper())
                val scanner = createScanner(scannerWrappers = listOf(scannerWrapper))

                every { scannerWrapper.scanPackage(any()) } answers {
                    ScanResult(
                        provenance = scanner.packageProvenanceResolver.resolveProvenance(
                            pkg = arg(0),
                            sourceCodeOriginPriority = scanner.downloaderConfig.sourceCodeOrigins
                        ),
                        scanner = scannerWrapper.details,
                        summary = emptyScanSummary
                    )
                }

                val result = scanner.scan(setOf(pkgWithArtifactProvenance, pkgWithRepositoryProvenance))

                result should containExactly(
                    pkgWithArtifactProvenance to createEmptyNestedScanResult(
                        artifactProvenance,
                        scannerWrapper.details
                    ),
                    pkgWithRepositoryProvenance to createEmptyNestedScanResult(
                        repositoryProvenance,
                        scannerWrapper.details
                    )
                )

                verify(exactly = 1) {
                    scannerWrapper.scanPackage(pkgWithArtifactProvenance)
                    scannerWrapper.scanPackage(pkgWithRepositoryProvenance)
                }
            }

            "not try to download the source code" {
                val provenanceDownloader = mockk<ProvenanceDownloader>()
                val scannerWrapper = FakePackageBasedRemoteScannerWrapper()
                val scanner = createScanner(
                    provenanceDownloader = provenanceDownloader,
                    scannerWrappers = listOf(scannerWrapper)
                )

                scanner.scan(setOf(pkgWithArtifactProvenance, pkgWithRepositoryProvenance))

                verify(exactly = 0) { provenanceDownloader.download(any(), any()) }
            }
        }

        "Scanning with a provenance based remote scanner" should {
            "return a scan result for each provenance" {
                val scannerWrapper = spyk(FakeProvenanceBasedRemoteScannerWrapper())
                val scanner = createScanner(scannerWrappers = listOf(scannerWrapper))

                val result = scanner.scan(setOf(pkgWithArtifactProvenance, pkgWithRepositoryProvenance))

                result should containExactly(
                    pkgWithArtifactProvenance to createEmptyNestedScanResult(
                        artifactProvenance,
                        scannerWrapper.details
                    ),
                    pkgWithRepositoryProvenance to createEmptyNestedScanResult(
                        repositoryProvenance,
                        scannerWrapper.details
                    )
                )

                verify(exactly = 1) {
                    scannerWrapper.scanProvenance(artifactProvenance)
                    scannerWrapper.scanProvenance(repositoryProvenance)
                }
            }

            "not try to download the source code" {
                val provenanceDownloader = mockk<ProvenanceDownloader>()
                val scannerWrapper = FakeProvenanceBasedRemoteScannerWrapper()
                val scanner = createScanner(
                    provenanceDownloader = provenanceDownloader,
                    scannerWrappers = listOf(scannerWrapper)
                )

                scanner.scan(setOf(pkgWithArtifactProvenance, pkgWithRepositoryProvenance))

                verify(exactly = 0) { provenanceDownloader.download(any(), any()) }
            }
        }

        // TODO: Make similar tests for each scanner type.
        // TODO: Make tests for each storage type.
    }
}

private val emptyScanSummary = ScanSummary(Instant.EPOCH, Instant.EPOCH, "", sortedSetOf(), sortedSetOf())

/**
 * An implementation of [PackageBasedRemoteScannerWrapper] that creates empty scan results.
 */
private class FakePackageBasedRemoteScannerWrapper : PackageBasedRemoteScannerWrapper {
    override val details: ScannerDetails = ScannerDetails("fake", "1.0.0", "config")
    override val name: String = details.name

    override fun getScannerCriteria(): ScannerCriteria = details.toCriteria()

    override fun scanPackage(pkg: Package): ScanResult = ScanResult(UnknownProvenance, details, emptyScanSummary)
}

/**
 * An implementation of [ProvenanceBasedRemoteScannerWrapper] that creates empty scan results.
 */
private class FakeProvenanceBasedRemoteScannerWrapper : ProvenanceBasedRemoteScannerWrapper {
    override val details: ScannerDetails = ScannerDetails("fake", "1.0.0", "config")
    override val name: String = details.name

    override fun getScannerCriteria(): ScannerCriteria = details.toCriteria()

    override fun scanProvenance(provenance: KnownProvenance): ScanResult =
        ScanResult(provenance, details, emptyScanSummary)
}

/**
 * An implementation of [LocalScannerWrapper] that creates scan results with one license finding for each file.
 */
private class FakeLocalScannerWrapper : LocalScannerWrapper {
    override val details: ScannerDetails = ScannerDetails("fake", "1.0.0", "config")
    override val name: String = details.name

    override fun getScannerCriteria(): ScannerCriteria = details.toCriteria()

    override fun scanPath(path: File): ScanResult {
        val licenseFindings = path.walk().filter { it.isFile }.map { file ->
            LicenseFinding("Apache-2.0", TextLocation(file.relativeTo(path).path, 1, 2))
        }.toSortedSet()

        return ScanResult(UnknownProvenance, details, emptyScanSummary.copy(licenseFindings = licenseFindings))
    }
}

/**
 * An implementation of [ProvenanceDownloader] that creates a file called "provenance.txt" containing the serialized
 * provenance, instead of actually downloading the source code.
 */
private class FakeProvenanceDownloader : ProvenanceDownloader {
    override fun download(provenance: KnownProvenance, downloadDir: File) {
        // TODO: Should downloadDir be created if it does not exist?
        val file = downloadDir.resolve("provenance.txt")
        file.writeText(yamlMapper.writeValueAsString(provenance))
    }
}

/**
 * An implementation of [PackageProvenanceResolver] that returns the values from the package without performing any
 * validation.
 */
private class FakePackageProvenanceResolver : PackageProvenanceResolver {
    override fun resolveProvenance(pkg: Package, sourceCodeOriginPriority: List<SourceCodeOrigin>): Provenance {
        sourceCodeOriginPriority.forEach { sourceCodeOrigin ->
            when (sourceCodeOrigin) {
                SourceCodeOrigin.ARTIFACT -> {
                    if (pkg.sourceArtifact != RemoteArtifact.EMPTY) {
                        return ArtifactProvenance(pkg.sourceArtifact)
                    }
                }

                SourceCodeOrigin.VCS -> {
                    if (pkg.vcsProcessed != VcsInfo.EMPTY) {
                        return RepositoryProvenance(pkg.vcsProcessed, "resolvedRevision")
                    }
                }
            }
        }

        return UnknownProvenance
    }
}

/**
 * An implementation of [NestedProvenanceResolver] that always returns a non-nested provenance.
 */
private class FakeNestedProvenanceResolver : NestedProvenanceResolver {
    override fun resolveNestedProvenance(provenance: KnownProvenance): NestedProvenance =
        NestedProvenance(root = provenance, subRepositories = emptyMap())
}

private fun createScanner(
    scannerConfig: ScannerConfiguration = ScannerConfiguration(),
    downloaderConfig: DownloaderConfiguration = DownloaderConfiguration(),
    provenanceDownloader: ProvenanceDownloader = FakeProvenanceDownloader(),
    storageReaders: List<ScanStorageReader> = emptyList(),
    storageWriters: List<ScanStorageWriter> = emptyList(),
    packageProvenanceResolver: PackageProvenanceResolver = FakePackageProvenanceResolver(),
    nestedProvenanceResolver: NestedProvenanceResolver = FakeNestedProvenanceResolver(),
    scannerWrappers: List<ScannerWrapper> = emptyList()
): ExperimentalScanner {
    return ExperimentalScanner(
        scannerConfig,
        downloaderConfig,
        provenanceDownloader,
        storageReaders,
        storageWriters,
        packageProvenanceResolver,
        nestedProvenanceResolver,
        scannerWrappers
    )
}

private fun ScannerDetails.toCriteria(): ScannerCriteria =
    ScannerCriteria(
        regScannerName = name,
        minVersion = Semver(version),
        maxVersion = Semver(version),
        configMatcher = { true }
    )

private fun createEmptyNestedScanResult(provenance: KnownProvenance, scannerDetails: ScannerDetails) =
    NestedProvenanceScanResult(
        NestedProvenance(root = provenance, subRepositories = emptyMap()),
        scanResults = mapOf(
            provenance to listOf(
                ScanResult(provenance, scannerDetails, emptyScanSummary)
            )
        )
    )
