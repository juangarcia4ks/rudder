module View exposing (..)

import DataTypes exposing (..)
import Html exposing (Html, button, div, i, span, text, h1, h4, ul, li, input, a, p, form, label, textarea, select, option, table, thead, tbody, tr, th, td, small)
import Html.Attributes exposing (id, class, type_, placeholder, value, for, href, colspan, rowspan, style, selected, disabled, attribute)
import Html.Events exposing (onClick, onInput)
import List.Extra
import List
import String exposing ( fromFloat)
import NaturalOrdering exposing (compareOn)
import ApiCalls exposing (..)
import ViewRulesTable exposing (..)
import ViewRuleDetails exposing (..)


view : Model -> Html Msg
view model =
  let
    ruleTreeElem : Rule -> Html Msg
    ruleTreeElem item =
      li [class "jstree-node jstree-leaf"]
      [ i[class "jstree-icon jstree-ocl"][]
      , a[href "#", class "jstree-anchor", onClick (OpenRuleDetails item.id)]
        [ i [class "jstree-icon jstree-themeicon fa fa-sitemap jstree-themeicon-custom"][]
        , span [class "treeGroupName tooltipable"][text item.name]
        ]
      ]

    ruleTreeCategory : (Category Rule) -> Html Msg
    ruleTreeCategory item =
      let
        categories = List.map ruleTreeCategory (getSubElems item)
        rules = List.map ruleTreeElem item.elems
        childsList  = ul[class "jstree-children"](categories ++ rules)
      in
        li[class "jstree-node jstree-open"]
        [ i[class "jstree-icon jstree-ocl"][]
        , a[href "#", class "jstree-anchor"]
          [ i [class "jstree-icon jstree-themeicon fa fa-folder jstree-themeicon-custom"][]
          , span [class "treeGroupCategoryName tooltipable"][text item.name]
          ]
        , childsList
        ]

    templateMain = case model.mode of
      Loading -> text "loading"
      RuleTable   ->
        div [class "main-details"]
        [ div [class "main-table"]
          [ table [ class "no-footer dataTable"]
            [ thead []
              [ tr [class "head"]
                [ th [class "sorting_asc", rowspan 1, colspan 1][text "Name"          ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Category"      ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Status"        ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Compliance"    ]
                , th [class "sorting"    , rowspan 1, colspan 1][text "Recent changes"]
                ]
              ]
            , tbody [] (buildRulesTable model)
            ]
          ]
        ]

      EditRule details ->
        (editionTemplate model details False)

      CreateRule details ->
        (editionTemplate model details True)

  in
    div [class "rudder-template"]
    [ div [class "template-sidebar sidebar-left"]
      [ div [class "sidebar-header"]
        [ div [class "header-title"]
          [ h1[]
            [ span[][text "Rules"]
            ]
          , div [class "header-buttons"]
            [ button [class "btn btn-default", type_ "button"][text "Add Category"]
            , button [class "btn btn-success", type_ "button", onClick (GenerateId (\s -> NewRule (RuleId s) ))][text "Create"]
            ]
          ]
        , div [class "header-filter"]
          [ div [class "input-group"]
            [ div [class "input-group-btn"]
              [ button [class "btn btn-default", type_ "button"][span [class "fa fa-folder fa-folder-open"][]]
              ]
            , input[type_ "text", placeholder "Filter", class "form-control"][]
            , div [class "input-group-btn"]
              [ button [class "btn btn-default", type_ "button"][span [class "fa fa-times"][]]
              ]
            ]
          ]
        ]
      , div [class "sidebar-body"]
        [ div [class "sidebar-list"]
          [ div [class "jstree jstree-default"]
            [ ul[class "jstree-container-ul jstree-children"][(ruleTreeCategory model.rulesTree) ]
            ]
          ]
        ]
      ]
    , div [class "template-main"]
      [ templateMain ]
    ]