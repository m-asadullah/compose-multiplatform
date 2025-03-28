/*
 * Copyright 2025 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package androidx.compose.xctest

import kotlinx.cinterop.*
import kotlin.native.internal.test.*
import platform.Foundation.*
import platform.Foundation.NSError
import platform.Foundation.NSInvocation
import platform.Foundation.NSString
import platform.Foundation.NSMethodSignature
import platform.UniformTypeIdentifiers.UTTypeSourceCode
import platform.XCTest.*
import platform.objc.*

/**
 * An XCTest equivalent of the K/N TestCase.
 *
 * Wraps the [TestCase] that runs it with a special bridge method created by adding it to a class.
 * The idea is to make XCTest invoke them by the created invocation and show the selector as a test name.
 * This selector is created as `class.method` that is than naturally represented in XCTest reports including XCode.
 */
internal class XCTestCaseWrapper(val testCase: TestCase) : XCTestCase(dummyInvocation()) {
    // Sets XCTest to continue running after failure to match Kotlin Test
    override fun continueAfterFailure(): Boolean = true

    val ignored = testCase.ignored || testCase.suite.ignored

    private val fullTestName = testCase.fullName

    init {
        // Set custom test name
        val newClass = NSClassFromString(testCase.suite.name)
            ?: objc_allocateClassPair(XCTestCaseWrapper.`class`(), testCase.suite.name, 0UL)!!.also {
                objc_registerClassPair(it)
            }

        object_setClass(this, newClass)
        val testName = if (ignored) {
            "[ignored] ${testCase.name}"
        } else {
            testCase.name
        }

        val selector = NSSelectorFromString(testName)
        createRunMethod(selector)
        setInvocation(methodSignatureForSelector(selector)?.let { signature ->
            @Suppress("CAST_NEVER_SUCCEEDS")
            val invocation = NSInvocation.invocationWithMethodSignature(signature as NSMethodSignature)
            invocation.setSelector(selector)
            invocation.setTarget(this)
            invocation
        })
    }

    /**
     * Creates and adds method to the metaclass with implementation block
     * that gets an XCTestCase instance as self to be run.
     */
    private fun createRunMethod(selector: COpaquePointer?) {
        val result = class_addMethod(
            cls = this.`class`(),
            name = selector,
            imp = imp_implementationWithBlock(::run),
            types = "v@:" // Obj-C type encodings: v (returns void), @ (id self), : (SEL sel)
        )
        check(result) {
            "Internal error: was unable to add method with selector $selector"
        }
    }

    @ObjCAction
    private fun run() {
        if (ignored) {
            // FIXME: to skip the test XCTSkip() should be used.
            //  But it is not possible to do that due to the KT-43719 and not implemented exception importing.
            //  For example, _XCTSkipHandler(testName, 0U, "Test $testName is ignored") fails with 'Uncaught Kotlin exception'.
            //  So, just don't run the test. It will be seen as passed in XCode, but K/N TestListener correctly processes that.
            return
        }
        try {
            testCase.doRun()
        } catch (throwable: Throwable) {
            val stackTrace = throwable.getStackTrace()
            val failedStackLine = stackTrace.first {
                // try to filter out kotlin.Exceptions and kotlin.test.Assertion inits to poin to the failed stack and line
                !it.contains("kfun:kotlin.")
            }
            // Find path and line number to create source location
            val matchResult = Regex("^\\d+ +.* \\((.*):(\\d+):.*\\)$").find(failedStackLine)
            val sourceLocation = if (matchResult != null) {
                val (file, line) = matchResult.destructured
                XCTSourceCodeLocation(file, line.toLong())
            } else {
                // No debug info to get the path. Still have to record location
                XCTSourceCodeLocation(testCase.suite.name, 0L)
            }

            // Make a stacktrace attachment, encoding it as source code.
            // This makes it appear as an attachment in the XCode test results for the failed test.
            @Suppress("CAST_NEVER_SUCCEEDS")
            val stackAsPayload = (stackTrace.joinToString("\n") as? NSString)?.dataUsingEncoding(NSUTF8StringEncoding)
            val stackTraceAttachment = XCTAttachment.attachmentWithUniformTypeIdentifier(
                identifier = UTTypeSourceCode.identifier,
                name = "Kotlin stacktrace (full)",
                payload = stackAsPayload,
                userInfo = null
            )

            val type = when (throwable) {
                is AssertionError -> XCTIssueTypeAssertionFailure
                else -> XCTIssueTypeUncaughtException
            }

            // Finally, create and record an issue with all gathered data
            val issue = XCTIssue(
                type = type,
                compactDescription = "$throwable in $fullTestName",
                detailedDescription = buildString {
                    appendLine("Test '$fullTestName' from '${testCase.suite.name}' failed with $throwable")
                    throwable.cause?.let { appendLine("(caused by ${throwable.cause})") }
                },
                sourceCodeContext = XCTSourceCodeContext(
                    callStackAddresses = throwable.getStackTraceAddresses(),
                    location = sourceLocation
                ),
                // pass the error through the XCTest to the NativeTestObserver
                associatedError = NSErrorWithKotlinException(throwable),
                attachments = listOf(stackTraceAttachment)
            )
            testRun?.recordIssue(issue) ?: error("TestRun for the test $fullTestName not found")
        }
    }

    override fun setUp() {
        if (!ignored) testCase.doBefore()
    }

    override fun tearDown() {
        if (!ignored) testCase.doAfter()
    }

    override fun description(): String = buildString {
        append(fullTestName)
        if (ignored) append("(ignored)")
    }

    override fun name(): String {
        return testCase.name
    }

    companion object : XCTestCaseMeta() {
        /**
         * This method is invoked by the XCTest when it discovered XCTestCase instance
         * that contains test method.
         *
         * This method should not be called with the current idea and assumptions.
         */
        override fun testCaseWithInvocation(invocation: NSInvocation?): XCTestCase {
            error(
                """
                This should not happen by default.
                Got invocation: ${invocation?.description}
                with selector @sel(${NSStringFromSelector(invocation?.selector)})
                """.trimIndent()
            )
        }

        private fun dummyInvocation(): NSInvocation {
            return NSInvocation.invocationWithMethodSignature(
                NSMethodSignature.signatureWithObjCTypes("v@:".cstr.getPointer(Arena())) as NSMethodSignature
            )
        }
    }
}

/**
 * This is a NSError-wrapper of Kotlin exception used to pass it through the XCTIssue
 * to the XCTestObservation protocol implementation [NativeTestObserver].
 * See [NativeTestObserver.testCase] for the usage.
 */
internal class NSErrorWithKotlinException(val kotlinException: Throwable) : NSError(NSCocoaErrorDomain, NSValidationErrorMinimum, null)

/**
 * XCTest equivalent of K/N TestSuite.
 */
internal class XCTestSuiteWrapper(val testSuite: TestSuite) : XCTestSuite(testSuite.name) {
    private val ignoredSuite: Boolean
        get() = testSuite.ignored || testSuite.testCases.all { it.value.ignored }

    override fun setUp() {
        if (!ignoredSuite) testSuite.doBeforeClass()
    }

    override fun tearDown() {
        if (!ignoredSuite) testSuite.doAfterClass()
    }
}
