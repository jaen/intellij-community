package com.intellij.driver.sdk.ui.components

import com.intellij.driver.client.Driver
import com.intellij.driver.model.RemoteMouseButton
import com.intellij.driver.sdk.screenshot.takeScreenshot
import com.intellij.driver.sdk.ui.DEFAULT_FIND_TIMEOUT
import com.intellij.driver.sdk.ui.Finder
import com.intellij.driver.sdk.ui.SearchContext
import com.intellij.driver.sdk.ui.UiText
import com.intellij.driver.sdk.ui.UiText.Companion.allText
import com.intellij.driver.sdk.ui.keyboard.WithKeyboard
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.ui.remote.Robot
import com.intellij.driver.sdk.ui.remote.RobotProvider
import com.intellij.driver.sdk.ui.remote.SearchService
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForOne
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


data class ComponentData(
  val xpath: String,
  val driver: Driver,
  val searchService: SearchService,
  val robotProvider: RobotProvider,
  val parentSearchContext: SearchContext,
  val foundComponent: Component?,
)

open class UiComponent(private val data: ComponentData) : Finder, WithKeyboard {
  companion object {
    private val LOG get() = logger<UiComponent>()

    /**
     * Waits until the element specified is found within the parent search context. Doesn't guaranty visibility.
     *
     * @param timeout The maximum time to wait for the element to not be found. If not specified, the default timeout is used.
     */
    fun <T : UiComponent> T.waitFound(timeout: Duration? = DEFAULT_FIND_TIMEOUT): T {
      findThisComponent(timeout)
      return this
    }

    /**
     * Asserts that the current UI component is found. Doesn't check visibility.
     *
     * @return The current UI component.
     */
    fun <T : UiComponent> T.assertFound(): T {
      assert(present()) { "Component '$this' should be found" }
      return this
    }

    /**
     * Waits until the element specified is not found within the parent search context.
     *
     * @param timeout The maximum time to wait for the element to not be found. If not specified, the default timeout is used.
     */
    fun <T : UiComponent> T.waitNotFound(timeout: Duration? = DEFAULT_FIND_TIMEOUT) {
      waitFor(message = "No ${this::class.simpleName}[xpath=${data.xpath}] in ${data.parentSearchContext.contextAsString}",
              timeout = timeout ?: DEFAULT_FIND_TIMEOUT,
              interval = 1.seconds) {
        kotlin.runCatching { findThisComponent(timeout) }.isFailure
      }
    }
    /**
     * Asserts that the calling UiComponent is not found in the hierarchy.
     */
    fun <T : UiComponent> T.assertNotFound() {
      assert(!present()) { "Component '$this' should not be found" }
    }
  }

  override fun toString(): String {
    return this::class.simpleName + "[xpath=${data.xpath}]"
  }

  private var cachedComponent: Component? = null
  val component: Component
    get() = data.foundComponent ?: kotlin.runCatching { cachedComponent?.takeIf { it.isShowing() } }.getOrNull()
            ?: findThisComponent().apply { cachedComponent = this }

  fun setFocus() {
    robot.focus(this.component)
  }

  private fun findThisComponent(timeout: Duration? = DEFAULT_FIND_TIMEOUT): Component =
    waitForOne(
      message = "Find ${this::class.simpleName}[xpath=${data.xpath}] in ${data.parentSearchContext.contextAsString}",
      timeout = timeout ?: DEFAULT_FIND_TIMEOUT,
      interval = 1.seconds,
      getter = { data.parentSearchContext.findAll(data.xpath) }
    )

  override val driver: Driver = data.driver
  override val searchService: SearchService = data.searchService
  override val robotProvider: RobotProvider = data.robotProvider

  val robot: Robot by lazy {
    data.robotProvider.getRobotFor(component)
  }

  override val searchContext: SearchContext = object : SearchContext {
    override val context: String = data.parentSearchContext.context + data.xpath

    override fun findAll(xpath: String): List<Component> {
      return searchService.findAll(xpath, component)
    }
  }

  /*
    Returns all UiText's matching predicate without waiting
   */
  fun getAllTexts(predicate: ((UiText) -> Boolean)? = null): List<UiText> {
    val allText = searchService.findAllText(component).map { UiText(this, it) }
    return predicate?.let { allText.filter(predicate) } ?: allText
  }

  /**
   * Returns all UiText objects matching the specified text without waiting.
   */
  fun getAllTexts(text: String): List<UiText> {
    return searchService.findAllText(component).map { UiText(this, it) }.filter { it.text == text }
  }

  fun allTextAsString(): String {
    return getAllTexts().allText()
  }

  /**
   * Waits for a non-empty list of UiText's.
   */
  fun waitAnyTexts(message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> {
    return waitFor(message = message ?: "Finding at least some texts in $this",
                   timeout = timeout,
                   getter = { getAllTexts() },
                   checker = { it.isNotEmpty() }
    )
  }

  /**
   * Waits for a non-empty list of UiText's matching predicate.
   */
  fun waitAnyTexts(message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT, predicate: (UiText) -> Boolean = { true }): List<UiText> {
    return waitFor(message = message ?: "Finding at least some texts and filter matching predicate in $this",
                   timeout = timeout,
                   getter = { getAllTexts() },
                   checker = { it.any(predicate) }
    ).filter(predicate)
  }

  /**
   * Waits for a non-empty list of UiText's with text '$text'.
   */
  fun waitAnyTexts(text: String, message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> {
    return waitFor(message = message ?: "Finding at least some texts and filter '$text' in $this",
                   timeout = timeout,
                   getter = { getAllTexts() },
                   checker = { it.any { it.text == text } }
    ).filter { it.text == text }
  }

  /**
   * Waits for one UiText with text '$text'.
   */
  fun waitOneText(text: String, message: String, timeout: Duration = DEFAULT_FIND_TIMEOUT): UiText {
    return waitForOne(message = message,
                      timeout = timeout,
                      getter = { getAllTexts() },
                      checker = { it.text == text }
    )
  }

  /**
   * Waits for one UiText with text '$text'.
   */
  fun waitOneText(text: String, timeout: Duration = DEFAULT_FIND_TIMEOUT): UiText {
    return waitOneText(message = "Finding text '$text' in $this", timeout = timeout, text = text)
  }

  /**
   * Waits for one UiText matching predicate.
   */
  fun waitOneText(message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT, predicate: (UiText) -> Boolean): UiText {
    return waitForOne(message = message ?: "Finding one text matching predicate in $this",
                      timeout = timeout,
                      getter = { getAllTexts() },
                      checker = { predicate(it) }
    )
  }

  /**
   * Waits until there is no UiText's.
   */
  fun waitNoTexts(message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> {
    return waitFor(message = message ?: "Finding no texts in $this",
                   timeout = timeout,
                   getter = { getAllTexts() },
                   checker = { it.isEmpty() }
    )
  }

  /**
   * Waits until there is no UiText's with text '$text'.
   */
  fun waitNoTexts(text: String, message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> {
    return waitFor(message = message ?: "Finding no texts '$text' in $this",
                   timeout = timeout,
                   getter = { getAllTexts() },
                   checker = { it.filter { it.text == text }.isEmpty() }
    )
  }

  /**
   * Waits for a non-empty list of UiText's with substring '$text'.
   */
  fun waitSomeTextsContains(text: String, message: String? = null, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> {
    return waitFor(message = message ?: "Finding at least some texts and contains '$text' in $this",
                   timeout = timeout,
                   getter = { getAllTexts() },
                   checker = { it.any { it.text.contains(text) } }
    )
  }

  /**
   * Waits until there is one UiText's with substring '$text'.
   */
  fun waitOneContainsText(text: String, message: String, ignoreCase: Boolean = true, timeout: Duration = DEFAULT_FIND_TIMEOUT): UiText {
    return waitForOne(message = message,
                      timeout = timeout,
                      getter = { getAllTexts() },
                      checker = { it.text.contains(text, ignoreCase = ignoreCase) }
    )
  }

  /**
   * Waits until there is one UiText's with substring '$text'.
   */
  fun waitOneContainsText(text: String, ignoreCase: Boolean = true, timeout: Duration = DEFAULT_FIND_TIMEOUT): UiText {
    return waitOneContainsText(message = "Finding the text containing '$text' in $this",
                               timeout = timeout, text = text, ignoreCase = ignoreCase)
  }

  /**
   * Waits until all text contains 'text'.
   */
  fun waitContainsText(text: String, message: String, ignoreCase: Boolean = true, timeout: Duration = DEFAULT_FIND_TIMEOUT) {
    waitFor(message = message,
            timeout = timeout,
            getter = { getAllTexts().allText() },
            checker = { it.contains(text, ignoreCase = ignoreCase) }
    )
  }

  /**
   * Waits until all text contains 'text'.
   */
  fun waitContainsText(text: String, ignoreCase: Boolean = true, timeout: Duration = DEFAULT_FIND_TIMEOUT) =
    waitContainsText(message = "Finding the text containing '$text' in $this", timeout = timeout, text = text, ignoreCase = ignoreCase)

  fun hasText(text: String): Boolean {
    return getAllTexts(text).isNotEmpty()
  }

  fun hasTextSequence(vararg texts: String, indexOffset: Int = 0): Boolean {
    require(indexOffset >= 0) { "Value must be non-negative" }
    val stringList = texts.toList()
    val uiTextList = getAllTexts()
    return stringList.indices.all { index ->
      val uiTextIndex = index + indexOffset
      uiTextIndex in uiTextList.indices && stringList[index] == uiTextList[uiTextIndex].text
    }
  }

  fun hasSubtext(subtext: String): Boolean {
    return getAllTexts { it.text.contains(subtext) }.isNotEmpty()
  }

  /**
   * Retrieves all UI text elements in a vertically ordered manner.
   *
   * This method returns a list of lists of `UiText` objects. Each inner list represents a collection of UI text
   * elements that are located at the same vertical position on the screen. The outer list represents the entire
   * collection of vertically ordered UI text elements.
   *
   * The UI text elements are ordered horizontally within each vertical position, based on their `x` coordinates.
   *
   * @return A list of lists of `UiText` objects, representing all UI text elements ordered vertically.
   */
  fun getAllVerticallyOrderedUiText(): List<List<UiText>> =
    getAllTexts().groupBy { it.point.y }.toSortedMap()
      .values.map { it.sortedBy { it.point.x } }.toList()

  /**
   * Waits for exactly one match in the list of vertically ordered UI text elements.
   * That list is originally returned by `getAllVerticallyOrderedUiText`
   *
   * This method waits until there is exactly one UI text element in the list that matches the specified conditions.
   * The conditions can be either a full match of the text or a partial match if the `fullMatch` parameter is set to `false`.
   *
   * @param message An optional message to display when waiting for a match.
   * @param text The text to match against the UI text elements.
   * @param fullMatch Flag to indicate whether the match should be a full match or partial match.
   * @param timeout The maximum time to wait for the match.
   * @return A list of `UiText` objects that match the specified conditions.
   */
  fun waitOneMatchInVerticallyOrderedText(message: String? = null, text: String, fullMatch: Boolean = true, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> =
    waitForOne(message, timeout,
               getter = { getAllVerticallyOrderedUiText() },
               checker = {
                 if (fullMatch) {
                   text == it.allText()
                 }
                 else {
                   it.allText().contains(text)
                 }
               })

  fun waitOneMatchInVerticallyOrderedText(text: String, fullMatch: Boolean = true, timeout: Duration = DEFAULT_FIND_TIMEOUT): List<UiText> =
    waitOneMatchInVerticallyOrderedText("Find '${text}'(fullMatch = $fullMatch) in vertically ordered text", text, fullMatch, timeout = timeout)

  fun present(): Boolean {
    return data.parentSearchContext.findAll(data.xpath).isNotEmpty()
  }

  fun notPresent(): Boolean {
    return data.parentSearchContext.findAll(data.xpath).isEmpty()
  }

  fun hasText(predicate: (UiText) -> Boolean): Boolean {
    return getAllTexts().any(predicate)
  }

  fun isVisible(): Boolean = component.isVisible()

  fun isEnabled(): Boolean = component.isEnabled()

  fun hasVisibleComponent(component: UiComponent): Boolean {
    val components = searchContext.findAll(component.data.xpath)
    if (components.isEmpty()) return false
    return components.any { it.isVisible() }
  }

  fun getParent(): UiComponent {
    return UiComponent(ComponentData(data.xpath + "/..", driver, searchService, robotProvider, data.parentSearchContext, component.getParent()))
  }

  fun getScreenshot(): BufferedImage {
    val screenshot = takeScreenshot(Rectangle(component.getLocationOnScreen().x, component.getLocationOnScreen().y, component.width, component.height))
    if (SystemInfo.isWindows && this is DialogUiComponent)
      return screenshot.getSubimage(
        7,
        0,
        component.width - 14,
        component.height - 7
      )
    return screenshot
  }

  // Mouse
  fun click(point: Point? = null) {
    if (point != null) {
      robot.click(component, point)
    }
    else {
      robot.click(component)
    }
  }

  fun doubleClick(point: Point? = null) {
    if (point != null) {
      robot.click(component, point, RemoteMouseButton.LEFT, 2)
    }
    else {
      robot.doubleClick(component)
    }
  }

  fun rightClick(point: Point? = null) {
    if (point != null) {
      robot.click(component, point, RemoteMouseButton.RIGHT, 1)
    }
    else {
      robot.rightClick(component)
    }
  }

  fun click(button: RemoteMouseButton, count: Int) {
    LOG.info("Click with $button $count times at $this")
    robot.click(component, button, count)
  }

  fun moveMouse() {
    robot.moveMouse(component)
  }

  fun moveMouse(point: Point) {
    robot.moveMouse(component, point)
  }

  fun hasFocus(): Boolean {
    return component.isFocusOwner()
  }

  fun mousePressMoveRelease(from: Point, to: Point) {
    robot.apply {
      moveMouse(component, from)
      pressMouse(RemoteMouseButton.LEFT)

      moveMouse(component, to)
      releaseMouse(RemoteMouseButton.LEFT)
    }
  }

  fun getBackgroundColor() = Color(component.getBackground().getRGB())

  fun getForegroundColor() = Color(component.getForeground().getRGB())

  fun waitIsFocusOwner() {
    waitFor {
      component.isFocusOwner()
    }
  }
}