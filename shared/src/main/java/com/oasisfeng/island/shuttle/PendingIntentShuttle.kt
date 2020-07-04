package com.oasisfeng.island.shuttle

import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.*
import android.content.*
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.M
import android.os.Build.VERSION_CODES.N
import android.util.Log
import android.view.*
import androidx.annotation.RequiresApi
import com.oasisfeng.island.util.Users
import com.oasisfeng.island.util.toId
import java.lang.reflect.Constructor
import java.lang.reflect.Field

@RequiresApi(M) class PendingIntentShuttle: BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.getLongExtra(ActivityOptions.EXTRA_USAGE_TIME_REPORT, -1) >= 0) return   // Ignore usage report
		val payload: Parcelable? = intent.getParcelableExtra(null)
		Log.d(TAG, "Received via shuttle: $payload")
		if (payload is PendingIntent && payload.creatorPackage == context.packageName) save(context, payload)
		else if (payload is Invocation) try {
			val constructor = payload.blockClass.declaredConstructors[0]
			constructor.isAccessible = true
			val args: Array<Any?> = payload.args
			val argTypes = constructor.parameterTypes
			argTypes.forEachIndexed { i, argType ->
				if (argType == Context::class.java) args[i] = context } // Fill in context
			val block: Context.() -> Unit = safeCast(constructor.newInstance(* args))
					?: throw IllegalArgumentException("Invalid block: " + payload.blockClass.name)
			block(context)
		} catch (t: Throwable) { Log.w(TAG, "Error executing " + payload.blockClass.name, t) }
	}

	private inline fun <reified T> safeCast(from: Any): T? = from as? T

	companion object {

		fun <A, B> shuttle(context: Context, profile: UserHandle, a: A, b: B, procedure: Context.(A, B) -> Unit)
				= shuttle(context, profile) { procedure(a, b) }

		fun <A, B, C> shuttle(context: Context, profile: UserHandle, a: A, b: B, c: C, procedure: Context.(A, B, C) -> Unit)
				= shuttle(context, profile) { procedure(a, b, c) }

		private fun shuttle(context: Context, profile: UserHandle, procedure: Context.() -> Unit): Boolean {
			return load(context, profile) { shuttle -> shuttle(context, shuttle, procedure) }
		}

		private fun shuttle(context: Context, shuttle: PendingIntent, procedure: Context.() -> Unit) {
			val javaClass = procedure.javaClass
			val constructors: Array<Constructor<*>> = javaClass.declaredConstructors
			require(constructors.isNotEmpty()) { "The method must have at least one constructor" }
			val constructor = constructors[0] // Extra constructor may be generated by "Instant Run" of Android Studio.
			val params = constructor.parameterTypes
			val fields: Array<Field> = javaClass.declaredFields
			val args: Array<Any?>
			if (params.isNotEmpty()) {
				val count = params.size
				require(fields.size >= count) { "Parameter types mismatch: " + constructor + " / " + fields.contentDeepToString() }
				args = arrayOfNulls(count)
				params.forEachIndexed { i, param ->
					try {
						val field = fields[(i + count - 1) % count] // Procedure is passed as last argument, but first field.
						require(field.type == param) { "Parameter types mismatch: " + constructor + " / " + fields.contentDeepToString() }
						field.isAccessible = true
						val arg = field.get(procedure)
						if (field.type != Context::class.java) args[i] = arg // Context argument is intentionally left blank.
					} catch (e: Exception) {
						throw IllegalArgumentException("Error enumerating lambda parameters.", e)
					}
				}
			} else args = arrayOfNulls(0)

			shuttle.send(context, 0, Intent().putExtra(null, Invocation(javaClass, args)))
		}

		fun sendToAllUnlockedProfiles(context: Context) {
			check(Users.isOwner()) { "Must be called in owner user" }
			context.getSystemService(UserManager::class.java)!!.userProfiles.dropWhile { it == Users.current() }.forEach {
				sendToProfileIfUnlocked(context, it) }
		}

		@RequiresApi(N) fun waitForProfileUnlockAndSend(context: Context): BroadcastReceiver {
			return object: BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) {
				val profile: UserHandle = intent.getParcelableExtra(Intent.EXTRA_USER) ?: return
				sendToProfileIfUnlocked(context, profile)
			}}.also { context.registerReceiver(it, IntentFilter(Intent.ACTION_MANAGED_PROFILE_UNLOCKED)) }
		}

		private fun sendToProfileIfUnlocked(context: Context, profile: UserHandle): Boolean {
			if (SDK_INT >= N && ! context.getSystemService(UserManager::class.java)!!.isUserUnlocked(profile))
				return false.also { Log.i(TAG, "Skip stopped or locked user: $profile") }
			val la = context.getSystemService(LauncherApps::class.java)!!
			la.getActivityList(context.packageName, profile).getOrNull(0)?.also {
				la.startMainActivity(it.componentName, profile, null, buildShuttleActivityOptions(context))
				Log.i(TAG, "Initializing shuttle to profile ${profile.toId()}...")
				return true } ?: Log.e(TAG, "No launcher activity in profile user ${profile.toId()}")
			return false
		}

		/** @param profile to retrieve shuttle or absent to build a new shuttle for current owner user (and send to profile later) */
		private fun buildShuttle(context: Context, profile: UserHandle = Users.current(), payload: Parcelable? = null, create: Boolean = payload != null)
				= PendingIntent.getBroadcast(context, profile.toId(),
				Intent(context, PendingIntentShuttle::class.java).apply { payload?.also { putExtra(null, it) }},
				if (create) FLAG_UPDATE_CURRENT else FLAG_NO_CREATE)

		private fun buildShuttleActivityOptions(context: Context): Bundle
				= ActivityOptions.makeSceneTransitionAnimation(context as? Activity ?: DummyActivity(context), View(context), "")
				.apply { requestUsageTimeReport(buildShuttle(context, create = true)) }.toBundle()

		@JvmStatic fun retrieveFromActivity(activity: Activity): PendingIntent? {
			@SuppressLint("PrivateApi") val options = Activity::class.java.getDeclaredMethod("getActivityOptions")
					.apply { isAccessible = true }.invoke(activity) as ActivityOptions?
			return options?.toBundle()?.getParcelable<PendingIntent>(KEY_USAGE_TIME_REPORT)?.also { shuttle ->
				save(activity, shuttle)
				val reverseShuttle = buildShuttle(activity, create = true)
				shuttle.send(activity, 0, Intent().putExtra(null, reverseShuttle))
			}
		}

		private fun save(context: Context, shuttle: PendingIntent) {
			val user = shuttle.creatorUserHandle?.takeIf { it != Users.current() }
					?: return Unit.also { Log.e(TAG, "Not a shuttle: $shuttle") }
			val locker = PendingIntent.getBroadcast(context, user.toId(),
					Intent(ACTION_SHUTTLE_LOCKER).setPackage("").putExtra(null, shuttle), FLAG_UPDATE_CURRENT)
			context.getSystemService(AlarmManager::class.java)!!.set(AlarmManager.ELAPSED_REALTIME,
					SystemClock.elapsedRealtime() + 365 * 24 * 3600_000L, locker)
		}

		private fun load(context: Context, profile: UserHandle, block: (PendingIntent) -> Unit): Boolean {
			require(profile != Users.current()) { "Same profile: $profile" }
			val locker = PendingIntent.getBroadcast(context, profile.toId(),
					Intent(ACTION_SHUTTLE_LOCKER).setPackage(""), FLAG_NO_CREATE) ?: return false
			locker.send(context, 0, null, { _, intent, _, _, _ ->
				block(intent.getParcelableExtra(null)!!) }, null)
			return true
		}

		private const val ACTION_SHUTTLE_LOCKER = "SHUTTLE_LOCKER"
		private const val KEY_USAGE_TIME_REPORT = "android:activity.usageTimeReport"
	}

	class Invocation(internal val blockClass: Class<*>, internal val args: Array<Any?>): Parcelable {

		override fun writeToParcel(dest: Parcel, flags: Int) = dest.run { writeString(blockClass.name); writeArray(args) }
		override fun describeContents() = 0
		constructor(parcel: Parcel, classLoader: ClassLoader)
				: this(classLoader.loadClass(parcel.readString()), parcel.readArray(classLoader)!!)

		companion object CREATOR : Parcelable.ClassLoaderCreator<Invocation> {
			override fun createFromParcel(parcel: Parcel, classLoader: ClassLoader) = Invocation(parcel, classLoader)
			override fun createFromParcel(parcel: Parcel) = Invocation(parcel, Invocation::class.java.classLoader!!)
			override fun newArray(size: Int): Array<Invocation?> = arrayOfNulls(size)
		}
	}

	class StarterService: Service() {   // TODO: PersistentService is unavailable if owner user is not managed by Island.

		override fun onCreate() {
			if (! Users.isOwner())
				return packageManager.setComponentEnabledSetting(ComponentName(this, javaClass),
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
			Log.i(TAG, "Initializing shuttles...")
			sendToAllUnlockedProfiles(this)
			if (SDK_INT >= N) mReceiver = waitForProfileUnlockAndSend(this)
		}

		override fun onDestroy() {
			if (! Users.isOwner()) return
			if (SDK_INT >= N) unregisterReceiver(mReceiver)
		}

		override fun onBind(intent: Intent?) = if (Users.isOwner()) Binder() else null

		@RequiresApi(N) private lateinit var mReceiver: BroadcastReceiver
	}

	private class DummyActivity(context: Context): Activity() {
		override fun getWindow() = mDummyWindow
		private val mDummyWindow = DummyWindow(context)
	}
	private class DummyWindow(context: Context): Window(context) {
		init { requestFeature(FEATURE_ACTIVITY_TRANSITIONS) }   // Required for ANIM_SCENE_TRANSITION. See ActivityOptions.makeSceneTransitionAnimation().
		override fun superDispatchTrackballEvent(event: MotionEvent?) = false
		override fun setNavigationBarColor(color: Int) {}
		override fun onConfigurationChanged(newConfig: Configuration?) {}
		override fun peekDecorView() = null
		override fun setFeatureDrawableUri(featureId: Int, uri: Uri?) {}
		override fun setVolumeControlStream(streamType: Int) {}
		override fun setBackgroundDrawable(drawable: Drawable?) {}
		override fun takeKeyEvents(get: Boolean) {}
		override fun getNavigationBarColor() = 0
		override fun superDispatchGenericMotionEvent(event: MotionEvent?) = false
		override fun superDispatchKeyEvent(event: KeyEvent?) = false
		override fun getLayoutInflater(): LayoutInflater = context.getSystemService(LayoutInflater::class.java)!!
		override fun performContextMenuIdentifierAction(id: Int, flags: Int) = false
		override fun setStatusBarColor(color: Int) {}
		override fun togglePanel(featureId: Int, event: KeyEvent?) {}
		override fun performPanelIdentifierAction(featureId: Int, id: Int, flags: Int) = false
		override fun closeAllPanels() {}
		override fun superDispatchKeyShortcutEvent(event: KeyEvent?) = false
		override fun superDispatchTouchEvent(event: MotionEvent?) = false
		override fun setDecorCaptionShade(decorCaptionShade: Int) {}
		override fun takeInputQueue(callback: InputQueue.Callback?) {}
		override fun setResizingCaptionDrawable(drawable: Drawable?) {}
		override fun performPanelShortcut(featureId: Int, keyCode: Int, event: KeyEvent?, flags: Int) = false
		override fun setFeatureDrawable(featureId: Int, drawable: Drawable?) {}
		override fun saveHierarchyState() = null
		override fun addContentView(view: View?, params: ViewGroup.LayoutParams?) {}
		override fun invalidatePanelMenu(featureId: Int) {}
		override fun setTitle(title: CharSequence?) {}
		override fun setChildDrawable(featureId: Int, drawable: Drawable?) {}
		override fun closePanel(featureId: Int) {}
		override fun restoreHierarchyState(savedInstanceState: Bundle?) {}
		override fun onActive() {}
		override fun getDecorView(): View { TODO("Not yet implemented") }
		override fun setTitleColor(textColor: Int) {}
		override fun setContentView(layoutResID: Int) {}
		override fun setContentView(view: View?) {}
		override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {}
		override fun getVolumeControlStream() = AudioManager.USE_DEFAULT_STREAM_TYPE
		override fun getCurrentFocus(): View? = null
		override fun getStatusBarColor() = 0
		override fun isShortcutKey(keyCode: Int, event: KeyEvent?) = false
		override fun setFeatureDrawableAlpha(featureId: Int, alpha: Int) {}
		override fun isFloating() = false
		override fun setFeatureDrawableResource(featureId: Int, resId: Int) {}
		override fun setFeatureInt(featureId: Int, value: Int) {}
		override fun setChildInt(featureId: Int, value: Int) {}
		override fun takeSurface(callback: SurfaceHolder.Callback2?) {}
		override fun openPanel(featureId: Int, event: KeyEvent?) {}
	}
}

private const val TAG = "Island.PIS"
