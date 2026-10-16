package app.novel.source.api

/**
 * Implemented by an extension that wants the host's network.
 *
 * The host checks for this interface right after it instantiates the extension class and, when it is
 * present, calls [bindNetwork] with its own [NovelNetwork] *before* the source is registered or
 * otherwise reachable. Until that call the instance has no network, so nothing may touch it in
 * between.
 *
 * The network arrives through a method rather than a constructor parameter on purpose. The host
 * creates the extension by reflection with a public no-argument constructor, so a required
 * constructor parameter would make the class uninstantiable. Injecting the network after
 * construction also keeps it out of any global or static state, so the same apk can be loaded more
 * than once without the instances sharing anything.
 */
interface NetworkedNovelSource : NovelSource {
    fun bindNetwork(network: NovelNetwork)
}
