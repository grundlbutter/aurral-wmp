import { useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { parseResponse } from "hono/client";
import { useAuth } from "../contexts/AuthContext";
import { useDocumentTitle } from "../hooks/useDocumentTitle";
import { useSiteLogo } from "../hooks/useSiteLogo";
import { usersClient } from "../utils/api/clients/users.ts";
import { OnboardingHint, OnboardingStep, OnboardingStepHeader } from "./onboardingUtils.tsx";

const APP_STATE = /^[A-Za-z0-9_-]{16,128}$/;

/** Hands the Aurral Android app this user's API key. It only ever redirects to the app's own scheme. */
export default function ConnectAppPage() {
  useDocumentTitle("Connect the Android app");
  const logo = useSiteLogo();
  const navigate = useNavigate();
  const { user } = useAuth();
  const state = new URLSearchParams(useLocation().search).get("state") ?? "";
  const [status, setStatus] = useState<"idle" | "busy" | "sent" | "failed">("idle");
  const valid = APP_STATE.test(state);

  async function connect() {
    setStatus("busy");
    try {
      const { apiKey } = await parseResponse(usersClient.me["api-key"].$get());
      const target = new URLSearchParams({ server: window.location.origin, key: apiKey, state });
      window.location.href = `aurral://connect?${target}`;
      setStatus("sent");
    } catch {
      setStatus("failed");
    }
  }

  return (
    <div className="onboarding-page">
      <div className="onboarding-card-shell">
        <div className="onboarding-card">
          <img
            src={logo.src}
            alt=""
            className={`onboarding-logo-anchor onboarding-logo-anchor--compact${logo.className}`}
          />
          <OnboardingStep>
            <OnboardingStepHeader title="Connect the Android app" />
            {valid ? (
              <OnboardingHint>
                The Aurral app on your phone will sign in as {user?.username ?? "you"} with your API
                key. To disconnect it later, rotate the key in Settings &gt; Profile.
              </OnboardingHint>
            ) : (
              <OnboardingHint>
                Open this page from the Aurral app&rsquo;s sign-in screen.
              </OnboardingHint>
            )}
            {status === "sent" ? (
              <OnboardingHint>Returning to the app. You can close this tab.</OnboardingHint>
            ) : null}
            {status === "failed" ? (
              <OnboardingHint>Couldn&rsquo;t load your API key. Try again.</OnboardingHint>
            ) : null}
          </OnboardingStep>
          <div className="onboarding-actions">
            <button type="button" className="btn btn-secondary" onClick={() => navigate("/")}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-primary btn--grow"
              disabled={!valid || status === "busy"}
              onClick={connect}
            >
              Connect
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
