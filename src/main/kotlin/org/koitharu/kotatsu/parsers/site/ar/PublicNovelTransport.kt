package org.koitharu.kotatsu.parsers.site.ar

import org.koitharu.kotatsu.parsers.exception.NotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** An unavailable network or a removed page cannot be fixed by browser verification. */
internal fun rethrowPublicPageNetworkError(error: Throwable?) {
	if (error == null) return
	if (generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(16).any {
		it is UnknownHostException || it is ConnectException || it is SocketTimeoutException ||
			it is SSLException || it is NotFoundException
	}) throw error
}
