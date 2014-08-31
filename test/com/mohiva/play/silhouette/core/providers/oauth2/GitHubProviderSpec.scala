/**
 * Copyright 2014 Mohiva Organisation (license at mohiva dot com)
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
package com.mohiva.play.silhouette.core.providers.oauth2

import test.Helper
import scala.concurrent.Future
import play.api.http.HeaderNames
import play.api.libs.json.Json
import play.api.libs.ws.{ WSResponse, WSRequestHolder }
import play.api.test.{ FakeRequest, WithApplication }
import com.mohiva.play.silhouette.core.providers._
import com.mohiva.play.silhouette.core.LoginInfo
import com.mohiva.play.silhouette.core.exceptions.{ ProfileRetrievalException, AuthenticationException }
import SocialProfileBuilder._
import GitHubProvider._
import OAuth2Provider._

/**
 * Test case for the [[com.mohiva.play.silhouette.core.providers.oauth2.GitHubProvider]] class.
 */
class GitHubProviderSpec extends OAuth2ProviderSpec {

  "The `authenticate` method" should {
    "fail with AuthenticationException if OAuth2Info can be build because of an unexpected response" in new WithApplication with Context {
      val requestHolder = mock[WSRequestHolder]
      val response = mock[WSResponse]
      implicit val req = FakeRequest(GET, "?" + Code + "=my.code")
      response.json returns Json.obj()
      requestHolder.withHeaders(HeaderNames.ACCEPT -> "application/json") returns requestHolder
      requestHolder.post[Map[String, Seq[String]]](any)(any, any) returns Future.successful(response)
      httpLayer.url(oAuthSettings.accessTokenURL) returns requestHolder
      stateProvider.validate(any)(any) returns Future.successful(state)

      failed[AuthenticationException](provider.authenticate()) {
        case e => e.getMessage must startWith(InvalidInfoFormat.format(provider.id, ""))
      }
    }

    "return the auth info" in new WithApplication with Context {
      val requestHolder = mock[WSRequestHolder]
      val response = mock[WSResponse]
      implicit val req = FakeRequest(GET, "?" + Code + "=my.code")
      response.json returns oAuthInfo
      requestHolder.withHeaders(any) returns requestHolder
      requestHolder.post[Map[String, Seq[String]]](any)(any, any) returns Future.successful(response)
      httpLayer.url(oAuthSettings.accessTokenURL) returns requestHolder
      stateProvider.validate(any)(any) returns Future.successful(state)

      authInfo(provider.authenticate()) {
        case authInfo => authInfo must be equalTo oAuthInfo.as[OAuth2Info]
      }
    }
  }

  "The `retrieveProfile` method" should {
    "fail with ProfileRetrievalException if API returns error" in new WithApplication with Context {
      val requestHolder = mock[WSRequestHolder]
      val response = mock[WSResponse]
      response.json returns Helper.loadJson("providers/oauth2/github.error.json")
      requestHolder.get() returns Future.successful(response)
      httpLayer.url(API.format("my.access.token")) returns requestHolder

      failed[ProfileRetrievalException](provider.retrieveProfile(oAuthInfo.as[OAuth2Info])) {
        case e => e.getMessage must equalTo(SpecifiedProfileError.format(
          provider.id,
          "Bad credentials",
          Some("http://developer.github.com/v3")))
      }
    }

    "fail with ProfileRetrievalException if an unexpected error occurred" in new WithApplication with Context {
      val requestHolder = mock[WSRequestHolder]
      val response = mock[WSResponse]
      response.json throws new RuntimeException("")
      requestHolder.get() returns Future.successful(response)
      httpLayer.url(API.format("my.access.token")) returns requestHolder

      failed[ProfileRetrievalException](provider.retrieveProfile(oAuthInfo.as[OAuth2Info])) {
        case e => e.getMessage must equalTo(UnspecifiedProfileError.format(provider.id))
      }
    }

    "return the social profile" in new WithApplication with Context {
      val requestHolder = mock[WSRequestHolder]
      val response = mock[WSResponse]
      response.json returns Helper.loadJson("providers/oauth2/github.success.json")
      requestHolder.get() returns Future.successful(response)
      httpLayer.url(API.format("my.access.token")) returns requestHolder

      profile(provider.retrieveProfile(oAuthInfo.as[OAuth2Info])) {
        case p =>
          p must be equalTo new CommonSocialProfile(
            loginInfo = LoginInfo(provider.id, "1"),
            fullName = Some("Apollonia Vanova"),
            email = Some("apollonia.vanova@watchmen.com"),
            avatarURL = Some("https://github.com/images/error/apollonia_vanova.gif")
          )
      }
    }
  }

  /**
   * Defines the context for the abstract OAuth2 provider spec.
   *
   * @return The Context to use for the abstract OAuth2 provider spec.
   */
  override protected def context: OAuth2ProviderSpecContext = new Context {}

  /**
   * The context.
   */
  trait Context extends OAuth2ProviderSpecContext {

    /**
     * The OAuth2 settings.
     */
    lazy val oAuthSettings = OAuth2Settings(
      authorizationURL = "https://github.com/login/oauth/authorize",
      accessTokenURL = "https://github.com/login/oauth/access_token",
      redirectURL = "https://www.mohiva.com",
      clientID = "my.client.id",
      clientSecret = "my.client.secret",
      scope = Some("repo,gist"))

    /**
     * The OAuth2 info returned by GitHub.
     *
     * @see http://vk.com/dev/auth_sites
     */
    override lazy val oAuthInfo = Helper.loadJson("providers/oauth2/github.access.token.json")

    /**
     * The provider to test.
     */
    lazy val provider = GitHubProvider(httpLayer, stateProvider, oAuthSettings)
  }
}
