package grants.playimpl

import play.api.mvc._
import play.api.mvc.Results._
import oauthorize.model._
import oauthorize.service._
import oauthorize.grants._
import json._
import scala.concurrent.Future
import play.api.libs.json.Json

trait Oauth2RequestValidatorPlay extends BodyReaderFilter with Oauth2RequestValidator with RenderingUtils {
  this: Oauth2Defaults with Oauth2Store with AuthzCodeGenerator =>

  override def bodyProcessor(a: OauthRequest, req: RequestHeader) = {
    logInfo(s"proceed with global validation at: $a")
    getErrors(a).map(maybeErr => maybeErr.map(renderErrorAsResult(_)))
  }
}

trait AccessTokenEndpointPlay extends BodyReaderFilter with AccessTokenEndpoint with RenderingUtils {
  this: Oauth2Defaults with PasswordEncoder with Oauth2Store with AuthzCodeGenerator =>

  override def bodyProcessor(oauthRequest: OauthRequest, req: RequestHeader) = {
    Option(processAccessTokenRequest(oauthRequest, BasicAuthentication(req)).map(_.fold(err => err, correct => correct)))
  }
}

trait RefreshTokenEndpointPlay extends BodyReaderFilter with RefreshTokenEndpoint with RenderingUtils {
  this: Oauth2Defaults with PasswordEncoder with Oauth2Store with AuthzCodeGenerator =>

  override def bodyProcessor(oauthRequest: OauthRequest, req: RequestHeader) = {
    Option(processRefreshTokenRequest(oauthRequest, BasicAuthentication(req)).map(_.fold(err => err, correct => correct)))
  }
}

trait ClientCredentialsGrantPlay extends BodyReaderFilter with ClientCredentialsGrant with RenderingUtils {
  this: Oauth2Defaults with PasswordEncoder with Oauth2Store with AuthzCodeGenerator =>

  override def bodyProcessor(oauthRequest: OauthRequest, req: RequestHeader) = {
    Option(processClientCredentialsRequest(oauthRequest, BasicAuthentication(req)).map(_.fold(err => err, correct => correct)))
  }
}

trait AuthorizationCodePlay extends BodyReaderFilter with AuthorizationCode with RenderingUtils {
  this: Oauth2Defaults with Oauth2Store with AuthzCodeGenerator =>

  override def bodyProcessor(a: OauthRequest, req: RequestHeader) = {
    Option(processAuthorizeRequest(a).map(_.fold(err => err, good => good)))
  }
}

trait ResourceOwnerCredentialsGrantPlay extends BodyReaderFilter with ResourceOwnerCredentialsGrant with RenderingUtils {
  this: Oauth2Defaults with PasswordEncoder with Oauth2Store with UserStore with AuthzCodeGenerator =>

  override def bodyProcessor(oauthRequest: OauthRequest, req: RequestHeader) = {
    Option(processOwnerCredentialsRequest(oauthRequest, BasicAuthentication(req)).map(_.fold(err => err, correct => correct)))
  }
}

trait ImplicitGrantPlay extends BodyReaderFilter with ImplicitGrant with RenderingUtils with securesocial.core.SecureSocial {
  this: Oauth2Defaults with Oauth2Store with AuthzCodeGenerator =>

  override def bodyProcessor(a: OauthRequest, req: RequestHeader) = {
    def process(u: Oauth2User): SimpleResult = processImplicitRequest(a, u).fold(err => err, good => transformReponse(good))
    Some(secureInvocation(process, req))
  }

  private def secureInvocation(block: (Oauth2User) => Result, req: RequestHeader) = {
    (SecuredAction { implicit r => block(UserExtractor(r)) })(req).run
  }

}

trait UserApprovalPlay extends BodyReaderFilter with UserApproval with RenderingUtils with securesocial.core.SecureSocial {
  this: Oauth2Defaults with Oauth2Store =>

  import oauth2.spec.Req._
  import oauthorize.utils._
  import oauth2.spec.AuthzErrors._
  import scala.concurrent.Await
  import scala.concurrent.duration._

  override def unmarshal(authzRequestJsonString: String) = Json.parse(authzRequestJsonString).asOpt[AuthzRequest]

  override def bodyProcessor(a: OauthRequest, req: RequestHeader) = {
    logInfo(s"processing user approval: $a");
    def lazyResult(u: Oauth2User) =
      if ("POST" == a.method || a.param(UserApproval.AutoApproveKey).map(_ == "true").getOrElse(false))
        lazyProcessApprove(a, u)
      else displayUserApprovalPage(a)
    Some(secureInvocation(lazyResult, req))
  }

  private def lazyProcessApprove(a: OauthRequest, u: Oauth2User) = {
    val res = processApprove(a, u)
    Redirect(res.uri, res.params.map(z => (z._1 -> Seq(z._2))), 302)
  }

  private def displayUserApprovalPage(a: OauthRequest) = {
    (for {
      authzCode <- a.param(code)
      authzRequestJsonString <- a.param(UserApproval.AuthzRequestKey)
      authzReq <- unmarshal(authzRequestJsonString)
      client <- getClient(authzReq.clientId)
    } yield {
      Ok(views.html.user_approval(authzCode, authzReq, authzRequestJsonString, client))
    }) getOrElse ({
      logError("Fatal error when initiating user approval after user authentication! The authorization code, authorization request or the client weren't found. Shouldn't have got here EVER, we're controlling the whole flow!")
      renderErrorAsResult(err(server_error, 500))
    })
  }

  private def secureInvocation(block: (Oauth2User) => Result, req: RequestHeader) = {
    (SecuredAction { implicit r => block(UserExtractor(r)) })(req).run
  }
}

private[playimpl] object UserExtractor {
  import securesocial.core.SecuredRequest
  import securesocial.core.providers.UsernamePasswordProvider.UsernamePassword
  def apply(r: SecuredRequest[_]) = {
    val emailOrElseId = if (r.user.identityId.providerId == UsernamePassword) r.user.identityId.userId else r.user.email.getOrElse(r.user.identityId.userId)
    oauthorize.model.Oauth2User(UserId(emailOrElseId, Option(r.user.identityId.providerId)))
  }
}
