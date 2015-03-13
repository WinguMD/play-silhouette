/**
 * Copyright 2015 Mohiva Organisation (license at mohiva dot com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mohiva.play.silhouette.impl.providers

import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.exceptions.{ AccessDeniedException, UnexpectedResponseException }
import com.mohiva.play.silhouette.impl.providers.OAuth1Provider._
import org.specs2.matcher.ThrownExpectations
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.mvc.{ Results, Result }
import play.api.test.{ FakeRequest, WithApplication }
import play.mvc.Http.HeaderNames

import scala.concurrent.Future

/**
 * Abstract test case for the [[OAuth1Provider]] class.
 *
 * These tests will be additionally executed before every OAuth1 provider spec.
 */
abstract class OAuth1ProviderSpec extends SocialProviderSpec[OAuth1Info] {
  isolated

  "The provider" should {
    val c = context
    "throw a RuntimeException if the unsafe 1.0 specification should be used" in new WithApplication {
      implicit val req = FakeRequest(GET, "?" + Denied + "=")
      c.oAuthService.use10a returns false
      c.provider.authenticate() must throwA[RuntimeException]
    }
  }

  "The authenticate method" should {
    val c = context
    "fail with an AccessDeniedException if denied key exists in query string" in new WithApplication {
      implicit val req = FakeRequest(GET, "?" + Denied + "=")
      failed[AccessDeniedException](c.provider.authenticate()) {
        case e => e.getMessage must startWith(AuthorizationError.format(c.provider.id, ""))
      }
    }

    "fail with an UnexpectedResponseException if request token cannot be retrieved" in new WithApplication {
      implicit val req = FakeRequest()
      c.oAuthService.retrieveRequestToken(c.oAuthSettings.callbackURL) returns Future.failed(new Exception(""))

      failed[UnexpectedResponseException](c.provider.authenticate()) {
        case e => e.getMessage must startWith(ErrorRequestToken.format(c.provider.id, ""))
      }
    }

    "redirect to authorization URL if request token could be retrieved" in new WithApplication {
      implicit val req = FakeRequest()
      val serializedTokenSecret = "my.serialized.token.secret"

      c.oAuthService.retrieveRequestToken(c.oAuthSettings.callbackURL) returns Future.successful(c.oAuthInfo)
      c.oAuthService.redirectUrl(any) returns c.oAuthSettings.authorizationURL
      c.oAuthTokenSecretProvider.build(any)(any) returns Future.successful(c.oAuthTokenSecret)
      c.oAuthTokenSecretProvider.publish(any, any)(any) answers { (a, m) =>
        a.asInstanceOf[Array[Any]](0).asInstanceOf[Result]
      }

      result(c.provider.authenticate()) {
        case result =>
          status(result) must equalTo(SEE_OTHER)
          redirectLocation(result) must beSome.which(_ == c.oAuthSettings.authorizationURL)
      }
    }

    "resolves relative redirectURLs before starting the flow" in new WithApplication {
      verifyCallbackURLResolution("/callback-url", false, "http://www.example.com/callback-url")
    }

    "resolves path relative redirectURLS before starting the flow" in new WithApplication {
      verifyCallbackURLResolution("callback-url", false, "http://www.example.com/request-path/callback-url")
    }

    "resolves relative redirectURLs before starting the flow over https" in new WithApplication {
      verifyCallbackURLResolution("/callback-url", true, "https://www.example.com/callback-url")
    }

    def verifyCallbackURLResolution(callbackURL: String, secure: Boolean, resolvedCallbackURL: String) = {
      implicit val req = spy(FakeRequest(GET, "/request-path/something").withHeaders(HeaderNames.HOST -> "www.example.com"))
      req.secure returns secure
      c.oAuthSettings.callbackURL returns callbackURL

      c.oAuthService.retrieveRequestToken(any) returns Future.successful(c.oAuthInfo)
      c.oAuthService.redirectUrl(c.oAuthInfo.token) returns c.oAuthSettings.authorizationURL
      c.oAuthTokenSecretProvider.build(any)(any) returns Future.successful(c.oAuthTokenSecret)
      c.oAuthTokenSecretProvider.publish(any, any)(any) returns Results.Redirect(c.oAuthSettings.authorizationURL)

      await(c.provider.authenticate())
      there was one(c.oAuthService).retrieveRequestToken(resolvedCallbackURL)
    }

    "fail with an UnexpectedResponseException if access token cannot be retrieved" in new WithApplication {
      val tokenSecret = "my.token.secret"
      implicit val req = FakeRequest(GET, "?" + OAuthVerifier + "=my.verifier&" + OAuthToken + "=my.token")

      c.oAuthTokenSecret.value returns tokenSecret
      c.oAuthTokenSecretProvider.retrieve(any)(any) returns Future.successful(c.oAuthTokenSecret)
      c.oAuthService.retrieveAccessToken(c.oAuthInfo.copy(secret = tokenSecret), "my.verifier") returns Future.failed(new Exception(""))

      failed[UnexpectedResponseException](c.provider.authenticate()) {
        case e => e.getMessage must startWith(ErrorAccessToken.format(c.provider.id, ""))
      }
    }

    "return the auth info" in new WithApplication {
      val tokenSecret = "my.token.secret"
      implicit val req = FakeRequest(GET, "?" + OAuthVerifier + "=my.verifier&" + OAuthToken + "=my.token")

      c.oAuthTokenSecret.value returns tokenSecret
      c.oAuthTokenSecretProvider.retrieve(any)(any) returns Future.successful(c.oAuthTokenSecret)
      c.oAuthService.retrieveAccessToken(c.oAuthInfo.copy(secret = tokenSecret), "my.verifier") returns Future.successful(c.oAuthInfo)

      authInfo(c.provider.authenticate()) {
        case authInfo => authInfo must be equalTo c.oAuthInfo
      }
    }
  }

  /**
   * Defines the context for the abstract OAuth1 provider spec.
   *
   * @return The Context to use for the abstract OAuth1 provider spec.
   */
  protected def context: OAuth1ProviderSpecContext
}

/**
 * Context for the OAuth1ProviderSpec.
 */
trait OAuth1ProviderSpecContext extends Scope with Mockito with ThrownExpectations {

  abstract class TestSecret extends OAuth1TokenSecret
  abstract class TestStateProvider extends OAuth1TokenSecretProvider {
    type Secret = TestSecret
  }

  /**
   * The HTTP layer mock.
   */
  lazy val httpLayer: HTTPLayer = mock[HTTPLayer]

  /**
   * A OAuth1 info.
   */
  lazy val oAuthInfo = OAuth1Info("my.token", "my.consumer.secret")

  /**
   * The OAuth1 service mock.
   */
  lazy val oAuthService: OAuth1Service = {
    val s = mock[OAuth1Service]
    s.use10a returns true
    s
  }

  /**
   * A OAuth1 token secret.
   */
  lazy val oAuthTokenSecret: TestSecret = mock[TestSecret]

  /**
   * The OAuth1 token secret provider mock.
   */
  lazy val oAuthTokenSecretProvider: TestStateProvider = mock[TestStateProvider]

  /**
   * The OAuth1 settings.
   */
  def oAuthSettings: OAuth1Settings

  /**
   * The provider to test.
   */
  def provider: OAuth1Provider
}
