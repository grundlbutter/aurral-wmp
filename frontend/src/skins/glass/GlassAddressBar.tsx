import { useLocation, useNavigate } from "react-router";
import RouteLink from "../../components/RouteLink";
import TooltipButton from "../../components/TooltipButton";
import { ArrowLeft, ArrowRight, ChevronRight } from "../../components/icons";
import { usePageTitle } from "../../hooks/useDocumentTitle.ts";
import { useSectionNav } from "../../navigation/useSectionNav.ts";
import { isRecord } from "../../../../lib/isRecord.ts";

export default function GlassAddressBar() {
  const navigate = useNavigate();
  const location = useLocation();
  const sectionNav = useSectionNav();
  const pageTitle = usePageTitle();

  const leaf = sectionNav?.root
    ? sectionNav.items.find((item) => item.active)?.label
    : sectionNav
      ? pageTitle
      : "";
  const sectionPath = sectionNav?.items[0]?.path;

  const goBack = () => {
    const historyState: unknown = window.history.state;
    if (isRecord(historyState) && Number(historyState.idx) > 0) navigate(-1);
    else navigate("/");
  };

  return (
    <div className="glass-address">
      <TooltipButton
        label="Back"
        className="glass-orb"
        onClick={goBack}
        disabled={location.key === "default" && location.pathname === "/"}
      >
        <ArrowLeft aria-hidden="true" />
      </TooltipButton>
      <TooltipButton label="Forward" className="glass-orb" onClick={() => navigate(1)}>
        <ArrowRight aria-hidden="true" />
      </TooltipButton>
      <nav className="glass-crumbs" aria-label="Location">
        {sectionNav ? (
          leaf && sectionPath ? (
            <RouteLink to={sectionPath} className="glass-crumbs__item">
              {sectionNav.title}
            </RouteLink>
          ) : (
            <span className="glass-crumbs__item glass-crumbs__item--current">
              {sectionNav.title}
            </span>
          )
        ) : (
          <span className="glass-crumbs__item glass-crumbs__item--current">
            {pageTitle || "Aurral"}
          </span>
        )}
        {leaf ? (
          <>
            <ChevronRight className="glass-crumbs__separator" aria-hidden="true" />
            <span className="glass-crumbs__item glass-crumbs__item--current" aria-current="page">
              {leaf}
            </span>
          </>
        ) : null}
      </nav>
    </div>
  );
}
